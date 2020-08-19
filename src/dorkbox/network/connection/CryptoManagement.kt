/*
 * Copyright 2020 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.network.connection

import dorkbox.netUtil.IPv4
import dorkbox.network.Configuration
import dorkbox.network.handshake.ClientConnectionInfo
import dorkbox.network.other.CryptoEccNative
import dorkbox.network.serialization.AeronInput
import dorkbox.network.serialization.AeronOutput
import dorkbox.network.storage.SettingsStore
import dorkbox.util.Sys
import dorkbox.util.entropy.Entropy
import dorkbox.util.exceptions.SecurityException
import mu.KLogger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


/**
 * Management for all of the crypto stuff used
 */
internal class CryptoManagement(val logger: KLogger,
                                private val settingsStore: SettingsStore,
                                type: Class<*>,
                                config: Configuration) {

    private val keyFactory = KeyFactory.getInstance("X25519")
    private val keyAgreement = KeyAgreement.getInstance("XDH")
    private val aesCipher = Cipher.getInstance("AES/GCM/PKCS5Padding")
    private val hash = MessageDigest.getInstance("SHA-256");

    companion object {
        const val AES_KEY_SIZE = 256
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH = 16
    }

    val privateKey: PrivateKey
    val publicKey: PublicKey
    val publicKeyBytes: ByteArray

    val secureRandom = SecureRandom(settingsStore.getSalt())

    private val enableRemoteSignatureValidation = config.enableRemoteSignatureValidation

    init {
        if (!enableRemoteSignatureValidation) {
            logger.warn("WARNING: Disabling remote key validation is a security risk!!")
        }

        // initialize the private/public keys used for negotiating ECC handshakes
        // these are ONLY used for IP connections. LOCAL connections do not need a handshake!
        var privateKeyBytes = settingsStore.getPrivateKey()
        var publicKeyBytes = settingsStore.getPublicKey()

        if (privateKeyBytes == null || publicKeyBytes == null) {
            try {
                // seed our RNG based off of this and create our ECC keys
                val seedBytes = Entropy.get("There are no ECC keys for the " + type.simpleName + " yet")
                logger.info("Now generating ECC (" + CryptoEccNative.curve25519 + ") keys. Please wait!")

                secureRandom.nextBytes(seedBytes)
                val generateKeyPair = createKeyPair(secureRandom)
                privateKeyBytes = generateKeyPair.private.encoded
                publicKeyBytes = generateKeyPair.public.encoded

                // save to properties file
                settingsStore.savePrivateKey(privateKeyBytes)
                settingsStore.savePublicKey(publicKeyBytes)
            } catch (e: Exception) {
                val message = "Unable to initialize/generate ECC keys. FORCED SHUTDOWN."
                logger.error(message, e)
                throw SecurityException(message, e)
            }
        }

        logger.info("ECC public key: ${Sys.bytesToHex(publicKeyBytes)}")

        this.privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes)) as XECPrivateKey
        this.publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes)) as XECPublicKey
        this.publicKeyBytes = publicKeyBytes!!
    }

    private fun createKeyPair(secureRandom: SecureRandom): KeyPair {
        val kpg: KeyPairGenerator = KeyPairGenerator.getInstance("XDH")
        kpg.initialize(NamedParameterSpec.X25519, secureRandom)
        return kpg.generateKeyPair()
    }



    /**
     * If the key does not match AND we have disabled remote key validation, then metachannel.changedRemoteKey = true. OTHERWISE, key validation is REQUIRED!
     *
     * @return true if all is OK (the remote address public key matches the one saved or we disabled remote key validation.)
     *         false if we should abort
     */
    internal fun validateRemoteAddress(remoteAddress: Int, publicKey: ByteArray?): PublicKeyValidationState {
        if (publicKey == null) {
            logger.error("Error validating public key for ${IPv4.toString(remoteAddress)}! It was null (and should not have been)")
            return PublicKeyValidationState.INVALID
        }

        try {
            val savedPublicKey = settingsStore.getRegisteredServerKey(remoteAddress)
            if (savedPublicKey == null) {
                logger.info("Adding new remote IP address key for ${IPv4.toString(remoteAddress)} : ${Sys.bytesToHex(publicKey)}")

                settingsStore.addRegisteredServerKey(remoteAddress, publicKey)
            } else {
                // COMPARE!
                if (!publicKey.contentEquals(savedPublicKey)) {
                    return if (enableRemoteSignatureValidation) {
                        // keys do not match, abort!
                        logger.error("The public key for remote connection ${IPv4.toString(remoteAddress)} does not match. Denying connection attempt")
                        PublicKeyValidationState.INVALID
                    }
                    else {
                        logger.warn("The public key for remote connection ${IPv4.toString(remoteAddress)} does not match. Permitting connection attempt.")
                        PublicKeyValidationState.TAMPERED
                    }
                }
            }
        } catch (e: SecurityException) {
            // keys do not match, abort!
            logger.error("Error validating public key for ${IPv4.toString(remoteAddress)}!", e)
            return PublicKeyValidationState.INVALID
        }

        return PublicKeyValidationState.VALID
    }

    /**
     * Generate the AES key based on ECDH
     */
    private fun generateAesKey(remotePublicKeyBytes: ByteArray, bytesA: ByteArray, bytesB: ByteArray): SecretKeySpec {
        val clientPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(remotePublicKeyBytes)) as XECPublicKey

        keyAgreement.init(privateKey)
        keyAgreement.doPhase(clientPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Derive a key from the shared secret and both public keys
        hash.reset()
        hash.update(sharedSecret)
        hash.update(bytesA)
        hash.update(bytesB)

        return SecretKeySpec(hash.digest(), "AES")
    }

    fun encrypt(publicationPort: Int,
                subscriptionPort: Int,
                connectionSessionId: Int,
                connectionStreamId: Int,
                clientPublicKeyBytes: ByteArray): ByteArray {

        val secretKeySpec = generateAesKey(clientPublicKeyBytes, clientPublicKeyBytes, publicKeyBytes)

        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv);

        val gcmParameterSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        aesCipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, gcmParameterSpec)

        // now create the byte array that holds all our data
        val data = AeronOutput()
        data.writeInt(connectionSessionId)
        data.writeInt(connectionStreamId)
        data.writeInt(publicationPort)
        data.writeInt(subscriptionPort)

        val bytes = data.toBytes()

        return iv + aesCipher.doFinal(bytes)
    }

    fun decrypt(registrationData: ByteArray?, serverPublicKeyBytes: ByteArray?): ClientConnectionInfo? {
        if (registrationData == null || serverPublicKeyBytes == null) {
            return null
        }

        val secretKeySpec = generateAesKey(serverPublicKeyBytes, publicKeyBytes, serverPublicKeyBytes)

        // now read the encrypted data
        val iv = ByteArray(GCM_IV_LENGTH)
        registrationData.copyInto(destination = iv,
                                  endIndex = GCM_IV_LENGTH)

        val secretBytes = ByteArray(registrationData.size - GCM_IV_LENGTH)
        registrationData.copyInto(destination = secretBytes,
                                  startIndex = GCM_IV_LENGTH)


        // now decrypt the data
        val gcmParameterSpec = GCMParameterSpec(GCM_TAG_LENGTH * 8, iv)
        aesCipher.init(Cipher.DECRYPT_MODE, secretKeySpec, gcmParameterSpec)

        val data = AeronInput(aesCipher.doFinal(secretBytes))

        // now read data off
        return ClientConnectionInfo(sessionId = data.readInt(),
                                    streamId = data.readInt(),
                                    publicationPort = data.readInt(),
                                    subscriptionPort = data.readInt(),
                                    publicKey = serverPublicKeyBytes)
    }

    override fun hashCode(): Int {
        val prime = 31
        var result = 1
        result = prime * result + publicKeyBytes.hashCode()
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other == null) {
            return false
        }
        if (javaClass != other.javaClass) {
            return false
        }

        val other1 = other as CryptoManagement
        if (!privateKey.encoded!!.contentEquals(other1.privateKey.encoded)) {
            return false
        }
        if (!publicKeyBytes.contentEquals(other1.publicKeyBytes)) {
            return false
        }

        return true
    }
}
