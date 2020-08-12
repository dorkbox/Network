@file:Suppress("DuplicatedCode")

package dorkbox.network.connection

import dorkbox.netUtil.IPv4
import dorkbox.network.Configuration
import dorkbox.network.handshake.ClientConnectionInfo
import dorkbox.network.other.CryptoEccNative
import dorkbox.network.pipeline.AeronInput
import dorkbox.network.pipeline.AeronOutput
import dorkbox.network.store.SettingsStore
import dorkbox.util.entropy.Entropy
import dorkbox.util.exceptions.SecurityException
import mu.KLogger
import java.security.*
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
        val AES_KEY_SIZE = 256
        val GCM_IV_LENGTH = 12
        val GCM_TAG_LENGTH = 16
    }




    val privateKey: PrivateKey
    val publicKey: PublicKey
    val publicKeyBytes: ByteArray

    val secureRandom: SecureRandom

    val enableRemoteSignatureValidation = config.enableRemoteSignatureValidation
    var disableRemoteKeyValidation = false

    init {
        secureRandom = SecureRandom(settingsStore.getSalt())

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

                logger.debug("Done with ECC keys!")
            } catch (e: Exception) {
                val message = "Unable to initialize/generate ECC keys. FORCED SHUTDOWN."
                logger.error(message, e)
                throw SecurityException(message, e)
            }
        }

        this.privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes)) as XECPrivateKey
        this.publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes)) as XECPublicKey
        this.publicKeyBytes = publicKeyBytes!!
    }

    fun createKeyPair(secureRandom: SecureRandom): KeyPair {
        val kpg: KeyPairGenerator = KeyPairGenerator.getInstance("XDH")
        kpg.initialize(NamedParameterSpec.X25519, secureRandom)
        return kpg.generateKeyPair()
    }

    /**
     * If the key does not match AND we have disabled remote key validation, then metachannel.changedRemoteKey = true. OTHERWISE, key validation is REQUIRED!
     *
     * @return true if the remote address public key matches the one saved or we disabled remote key validation.
     */
    internal fun validateRemoteAddress(remoteAddress: Int, publicKey: ByteArray?): Boolean {
        if (publicKey == null) {
            logger.error("Error validating public key for ${IPv4.toString(remoteAddress)}! It was null (and should not have been)")
            return false
        }

        try {
            val savedPublicKey = settingsStore.getRegisteredServerKey(remoteAddress)
            if (savedPublicKey == null) {
                if (logger.isDebugEnabled) {
                    logger.debug("Adding new remote IP address key for ${IPv4.toString(remoteAddress)}")
                }

                settingsStore.addRegisteredServerKey(remoteAddress, publicKey)
            } else {
                // COMPARE!
                if (!publicKey.contentEquals(savedPublicKey)) {
                    return if (!enableRemoteSignatureValidation) {
                        logger.warn("Invalid or non-matching public key from remote connection, their public key has changed. Toggling extra flag in channel to indicate key change. To fix, remove entry for: ${IPv4.toString(remoteAddress)}")
                        true
                    } else {
                        // keys do not match, abort!
                        logger.error("Invalid or non-matching public key from remote connection, their public key has changed. To fix, remove entry for: ${IPv4.toString(remoteAddress)}")
                        false
                    }
                }
            }
        } catch (e: SecurityException) {
            // keys do not match, abort!
            logger.error("Error validating public key for ${IPv4.toString(remoteAddress)}!", e)
            return false
        }

        return true
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

    fun encrypt(publicationPort: Int,
                subscriptionPort: Int,
                connectionSessionId: Int,
                connectionStreamId: Int,
                clientPublicKeyBytes: ByteArray): ByteArray {

        val clientPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(clientPublicKeyBytes)) as XECPublicKey

        keyAgreement.init(privateKey)
        keyAgreement.doPhase(clientPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Derive a key from the shared secret and both public keys
        hash.reset()
        hash.update(sharedSecret)
        hash.update(clientPublicKeyBytes)
        hash.update(publicKeyBytes)

        val keyBytes = hash.digest()
        val secretKeySpec = SecretKeySpec(keyBytes, "AES")


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

        val serverPublicKey = keyFactory.generatePublic(X509EncodedKeySpec(serverPublicKeyBytes)) as XECPublicKey

        keyAgreement.init(privateKey)
        keyAgreement.doPhase(serverPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // Derive a key from the shared secret and both public keys
        hash.reset()
        hash.update(sharedSecret)
        hash.update(publicKeyBytes)
        hash.update(serverPublicKeyBytes)

        val keyBytes = hash.digest()
        val secretKeySpec = SecretKeySpec(keyBytes, "AES")


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
                                    // NOTE: pub/sub must be switched!
                                    subscriptionPort = data.readInt(),
                                    publicationPort = data.readInt(),
                                    publicKey = publicKeyBytes)
    }
}
