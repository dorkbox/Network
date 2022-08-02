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

import dorkbox.bytes.Hash
import dorkbox.bytes.toHexString
import dorkbox.network.handshake.ClientConnectionInfo
import dorkbox.network.serialization.AeronInput
import dorkbox.network.serialization.AeronOutput
import dorkbox.network.serialization.SettingsStore
import dorkbox.util.entropy.Entropy
import mu.KLogger
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPrivateKeySpec
import java.security.spec.XECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


/**
 * Management for all the crypto stuff used
 */
internal class CryptoManagement(val logger: KLogger,
                                private val settingsStore: SettingsStore,
                                type: Class<*>,
                                private val enableRemoteSignatureValidation: Boolean) {

    private val X25519 = "X25519"
    private val X25519KeySpec = NamedParameterSpec(X25519)

    private val keyFactory = KeyFactory.getInstance(X25519)  // key size is 32 bytes (256 bits)
    private val keyAgreement = KeyAgreement.getInstance("XDH")

    private val aesCipher = Cipher.getInstance("AES/GCM/NoPadding")

    companion object {
        const val curve25519 = "curve25519"
        const val GCM_IV_LENGTH_BYTES = 12
        const val GCM_TAG_LENGTH_BITS = 128
    }

    val privateKey: XECPrivateKey
    val publicKey: XECPublicKey

    val privateKeyBytes: ByteArray
    val publicKeyBytes: ByteArray

    val secureRandom = SecureRandom(settingsStore.getSalt())

    private val iv = ByteArray(GCM_IV_LENGTH_BYTES)
    val cryptOutput = AeronOutput()
    val cryptInput = AeronInput()

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
                val seedBytes = Entropy["There are no ECC keys for the ${type.simpleName} yet"]
                logger.debug("Now generating ECC ($curve25519) keys. Please wait!")

                secureRandom.nextBytes(seedBytes)

                // see: https://stackoverflow.com/questions/60303561/how-do-i-pass-a-44-bytes-x25519-public-key-created-by-openssl-to-cryptokit-which
                val (xdhPublic, xdhPrivate) = createKeyPair(secureRandom)

                publicKeyBytes = xdhPublic.u.toByteArray()
                privateKeyBytes = xdhPrivate.scalar

                // save to properties file
                settingsStore.savePrivateKey(privateKeyBytes)
                settingsStore.savePublicKey(publicKeyBytes)
            } catch (e: Exception) {
                val message = "Unable to initialize/generate ECC keys. FORCED SHUTDOWN."
                logger.error(message, e)
                throw SecurityException(message, e)
            }
        }

        publicKeyBytes!!

        logger.info("ECC public key: ${publicKeyBytes.toHexString()}")

        this.publicKey = keyFactory.generatePublic(XECPublicKeySpec(X25519KeySpec, BigInteger(publicKeyBytes))) as XECPublicKey
        this.privateKey = keyFactory.generatePrivate(XECPrivateKeySpec(X25519KeySpec, privateKeyBytes)) as XECPrivateKey

        this.privateKeyBytes = privateKeyBytes!!
        this.publicKeyBytes = publicKeyBytes
    }

    private fun createKeyPair(secureRandom: SecureRandom): Pair<XECPublicKeySpec, XECPrivateKeySpec> {
        val kpg: KeyPairGenerator = KeyPairGenerator.getInstance("XDH")
        kpg.initialize(NamedParameterSpec.X25519, secureRandom)
        val keyPair = kpg.generateKeyPair()

        // get the ACTUAL key spec, so we can get the ACTUAL key byte arrays
        val xdhPublic: XECPublicKeySpec = keyFactory.getKeySpec(keyPair.public, XECPublicKeySpec::class.java)
        val xdhPrivate: XECPrivateKeySpec = keyFactory.getKeySpec(keyPair.private, XECPrivateKeySpec::class.java)

        return Pair(xdhPublic, xdhPrivate)
    }

    /**
     * If the key does not match AND we have disabled remote key validation -- key validation is REQUIRED!
     *
     * @return true if all is OK (the remote address public key matches the one saved or we disabled remote key validation.)
     *         false if we should abort
     */
    internal fun validateRemoteAddress(remoteAddress: InetAddress, remoteAddressString: String, publicKey: ByteArray?): PublicKeyValidationState {
        if (publicKey == null) {
            logger.error("Error validating public key for ${remoteAddressString}! It was null (and should not have been)")
            return PublicKeyValidationState.INVALID
        }

        try {
            val savedPublicKey = settingsStore.getRegisteredServerKey(remoteAddress)
            if (savedPublicKey != null) {
                // COMPARE!
                if (!publicKey.contentEquals(savedPublicKey)) {
                    return if (enableRemoteSignatureValidation) {
                        // keys do not match, abort!
                        logger.error("The public key for remote connection $remoteAddressString does not match. Denying connection attempt")
                        PublicKeyValidationState.INVALID
                    }
                    else {
                        logger.warn("The public key for remote connection $remoteAddressString does not match. Permitting connection attempt.")
                        PublicKeyValidationState.TAMPERED
                    }
                }
            }
        } catch (e: SecurityException) {
            // keys do not match, abort!
            logger.error("Error validating public key for $remoteAddressString!", e)
            return PublicKeyValidationState.INVALID
        }

        return PublicKeyValidationState.VALID
    }

    /**
     * Generate the AES key based on ECDH
     */
    private fun generateAesKey(remotePublicKeyBytes: ByteArray, bytesA: ByteArray, bytesB: ByteArray): SecretKeySpec {
        val clientPublicKey = keyFactory.generatePublic(XECPublicKeySpec(X25519KeySpec, BigInteger(remotePublicKeyBytes)))
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(clientPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Derive a key from the shared secret and both public keys
        val hash = Hash.sha256
        hash.reset()
        hash.update(sharedSecret)
        hash.update(bytesA)
        hash.update(bytesB)

        return SecretKeySpec(hash.digest(), "AES")
    }

    // NOTE: ALWAYS CALLED ON THE SAME THREAD! (from the server, mutually exclusive calls to decrypt)
    fun encrypt(clientPublicKeyBytes: ByteArray,
                subscriptionPort: Int,
                connectionSessionId: Int,
                connectionStreamId: Int,
                kryoRegDetails: ByteArray): ByteArray {

        try {
            val secretKeySpec = generateAesKey(clientPublicKeyBytes, clientPublicKeyBytes, publicKeyBytes)
            secureRandom.nextBytes(iv)

            val gcmParameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            aesCipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, gcmParameterSpec)

            // now create the byte array that holds all our data
            cryptOutput.reset()
            cryptOutput.writeInt(connectionSessionId)
            cryptOutput.writeInt(connectionStreamId)
            cryptOutput.writeInt(subscriptionPort)
            cryptOutput.writeInt(kryoRegDetails.size)
            cryptOutput.writeBytes(kryoRegDetails)

            return iv + aesCipher.doFinal(cryptOutput.toBytes())
        } catch (e: Exception) {
            logger.error("Error during AES encrypt", e)
            return ByteArray(0)
        }
    }

    // NOTE: ALWAYS CALLED ON THE SAME THREAD! (from the client, mutually exclusive calls to encrypt)
    fun decrypt(registrationData: ByteArray, serverPublicKeyBytes: ByteArray): ClientConnectionInfo? {
        try {
            val secretKeySpec = generateAesKey(serverPublicKeyBytes, publicKeyBytes, serverPublicKeyBytes)

            // now decrypt the data
            val gcmParameterSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, registrationData, 0, GCM_IV_LENGTH_BYTES)
            aesCipher.init(Cipher.DECRYPT_MODE, secretKeySpec, gcmParameterSpec)

            cryptInput.buffer = aesCipher.doFinal(registrationData, GCM_IV_LENGTH_BYTES, registrationData.size - GCM_IV_LENGTH_BYTES)

            val sessionId = cryptInput.readInt()
            val streamId = cryptInput.readInt()
            val subscriptionPort = cryptInput.readInt()
            val regDetailsSize = cryptInput.readInt()
            val regDetails = cryptInput.readBytes(regDetailsSize)

            // now read data off
            return ClientConnectionInfo(sessionId = sessionId,
                                        streamId = streamId,
                                        port = subscriptionPort,
                                        publicKey = serverPublicKeyBytes,
                                        kryoRegistrationDetails = regDetails)
        } catch (e: Exception) {
            logger.error("Error during AES decrypt!", e)
            return null
        }
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
