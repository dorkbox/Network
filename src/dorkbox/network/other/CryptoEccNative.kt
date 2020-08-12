package dorkbox.network.other

import java.math.BigInteger
import java.security.*
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import javax.crypto.Cipher



/**
 *
 */
object CryptoEccNative {
    // see: https://openjdk.java.net/jeps/324

    const val curve25519 = "curve25519"
    const val default_curve = curve25519

    const val macSize = 512
    // on NIST vs 25519 vs Brainpool, see:
    //  - http://ogryb.blogspot.de/2014/11/why-i-dont-trust-nist-p-256.html
    //  - http://credelius.com/credelius/?p=97
    //  - http://safecurves.cr.yp.to/
    // we should be using 25519, because NIST and brainpool are "unsafe". Brainpool is "more random" than 25519, but is still not considered safe.

    // more info about ECC from:
    // http://www.johannes-bauer.com/compsci/ecc/?menuid=4
    // http://stackoverflow.com/questions/7419183/problems-implementing-ecdh-on-android-using-bouncycastle
    // http://tools.ietf.org/html/draft-jivsov-openpgp-ecc-06#page-4
    // http://www.nsa.gov/ia/programs/suiteb_cryptography/
    // https://github.com/nelenkov/ecdh-kx/blob/master/src/org/nick/ecdhkx/Crypto.java
    // http://nelenkov.blogspot.com/2011/12/using-ecdh-on-android.html
    // http://www.secg.org/collateral/sec1_final.pdf

    // More info about 25519 key types (ed25519 and X25519)
    // https://blog.filippo.io/using-ed25519-keys-for-encryption/


    fun createKeyPair(secureRandom: SecureRandom): KeyPair {
        val kpg: KeyPairGenerator = KeyPairGenerator.getInstance("XDH")
        kpg.initialize(NamedParameterSpec.X25519, secureRandom)
        return kpg.generateKeyPair()


//        println("--- Public Key ---")
//        val publicKey = kp.public
//
//        System.out.println(publicKey.algorithm) // XDH
//        System.out.println(publicKey.format)    // X.509
//
//        // save this public key
//        val pubKey = publicKey.encoded
//
//        println("---")
//
//        println("--- Private Key ---")
//        val privateKey = kp.private
//
//        System.out.println(privateKey.algorithm);  // XDH
//        System.out.println(privateKey.format);     // PKCS#8
//
//        // save this private key
//        val priKey = privateKey.encoded


//        val kf: KeyFactory = KeyFactory.getInstance("XDH");

//        //BigInteger u = ...
//        val pubSpec: XECPublicKeySpec = XECPublicKeySpec(paramSpec, u);
//        val pubKey: PublicKey = kf.generatePublic(pubSpec);
//        //
//
//        val ka: KeyAgreement = KeyAgreement.getInstance("XDH");
//        ka.init(kp.private);
        //ka.doPhase(pubKey, true);
        //byte[] secret = ka.generateSecret();
    }



    private val FieldP_2: BigInteger = BigInteger.TWO // constant for scalar operations
    private val FieldP_3: BigInteger = BigInteger.valueOf(3) // constant for scalar operations
    private const val byteVal1 = 1.toByte()

    @Throws(GeneralSecurityException::class)
    fun getPublicKey(pk: ECPrivateKey): ECPublicKey? {
        val params: ECParameterSpec = pk.params
        val w: ECPoint = scalmultNew(params, params.generator, pk.s)

        //final ECPoint w = scalmult(params.getCurve(), pk.getParams().getGenerator(), pk.getS());
        val kg: KeyFactory = KeyFactory.getInstance("EC")
        return kg.generatePublic(ECPublicKeySpec(w, params)) as ECPublicKey
    }

    private fun scalmultNew(params: ECParameterSpec, g: ECPoint, kin: BigInteger): ECPoint {
        val curve = params.curve
        val field = curve.field
        if (field !is ECFieldFp) throw java.lang.UnsupportedOperationException(field::class.java.canonicalName)

        val p = field.p
        val a = curve.a
        var R = ECPoint.POINT_INFINITY

        // value only valid for curve secp256k1, code taken from https://www.secg.org/sec2-v2.pdf,
        // see "Finally the order n of G and the cofactor are: n = "FF.."
        val SECP256K1_Q = params.order
        //BigInteger SECP256K1_Q = new BigInteger("00FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141",16);
        var k = kin.mod(SECP256K1_Q) // uses this !
        // BigInteger k = kin.mod(p); // do not use this ! wrong as per comment from President James Moveon Polk
        val length = k.bitLength()
        val binarray = ByteArray(length)

        for (i in 0..length - 1) {
            binarray[i] = k.mod(FieldP_2).byteValueExact()
            k = k.shiftRight(1)
        }
        for (i in length - 1 downTo 0) {
            R = doublePoint(p, a, R)
            if (binarray[i] == byteVal1) R = addPoint(p, a, R, g)
        }

        return R
    }

    fun scalmultOrg(curve: EllipticCurve, g: ECPoint, kin: BigInteger): ECPoint {
        val field: ECField = curve.getField()
        if (field !is ECFieldFp) throw UnsupportedOperationException(field::class.java.canonicalName)
        val p: BigInteger = (field as ECFieldFp).getP()
        val a: BigInteger = curve.getA()
        var R = ECPoint.POINT_INFINITY
        // value only valid for curve secp256k1, code taken from https://www.secg.org/sec2-v2.pdf,
        // see "Finally the order n of G and the cofactor are: n = "FF.."
        val SECP256K1_Q = BigInteger("00FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
        var k = kin.mod(SECP256K1_Q) // uses this !

        // wrong as per comment from President James Moveon Polk
        // BigInteger k = kin.mod(p); // do not use this !
        println(" SECP256K1_Q: $SECP256K1_Q")
        println("           p: $p")

        System.out.println("curve: " + curve.toString())
        val length = k.bitLength()
        val binarray = ByteArray(length)
        for (i in 0..length - 1) {
            binarray[i] = k.mod(FieldP_2).byteValueExact()
            k = k.shiftRight(1)
        }
        for (i in length - 1 downTo 0) {
            R = doublePoint(p, a, R)
            if (binarray[i] == byteVal1) R = addPoint(p, a, R, g)
        }
        return R
    }

    // scalar operations for native java
    // https://stackoverflow.com/a/42797410/8166854
    // written by author: SkateScout
    private fun doublePoint(p: BigInteger, a: BigInteger, R: ECPoint): ECPoint? {
        if (R == ECPoint.POINT_INFINITY) return R

        var slope = R.affineX.pow(2).multiply(FieldP_3)
        slope = slope.add(a)
        slope = slope.multiply(R.affineY.multiply(FieldP_2).modInverse(p))

        val Xout = slope.pow(2).subtract(R.affineX.multiply(FieldP_2)).mod(p)
        val Yout = R.affineY.negate().add(slope.multiply(R.affineX.subtract(Xout))).mod(p)

        return ECPoint(Xout, Yout)
    }

    private fun addPoint(p: BigInteger, a: BigInteger, r: ECPoint, g: ECPoint): ECPoint? {
        if (r == ECPoint.POINT_INFINITY) return g
        if (g == ECPoint.POINT_INFINITY) return r

        if (r == g || r == g) return doublePoint(p, a, r)

        val gX = g.affineX
        val sY = g.affineY
        val rX = r.affineX
        val rY = r.affineY

        val slope = rY.subtract(sY).multiply(rX.subtract(gX).modInverse(p)).mod(p)
        val Xout = slope.modPow(FieldP_2, p).subtract(rX).subtract(gX).mod(p)
        var Yout = sY.negate().mod(p)
        Yout = Yout.add(slope.multiply(gX.subtract(Xout))).mod(p)
        return ECPoint(Xout, Yout)
    }


    private fun byteArrayToHexString(a: ByteArray): String {
        val sb = StringBuilder(a.size * 2)
        for (b in a) sb.append(String.format("%02X", b))
        return sb.toString()
    }

    fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4)
                    + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    @Throws(GeneralSecurityException::class)
    @JvmStatic
    fun main(args: Array<String>) {
        val cryptoText = "i23j4jh234kjh234kjh23lkjnfa9s8egfuypuh325"

        // NOTE: THIS IS NOT 25519!!
        println("Generate ECPublicKey from PrivateKey (String) for curve secp256k1 (final)")
        println("Check keys with https://gobittest.appspot.com/Address")

        // https://gobittest.appspot.com/Address
        val privateKey = "D12D2FACA9AD92828D89683778CB8DFCCDBD6C9E92F6AB7D6065E8AACC1FF6D6"
        val publicKeyExpected = "04661BA57FED0D115222E30FE7E9509325EE30E7E284D3641E6FB5E67368C2DB185ADA8EFC5DC43AF6BF474A41ED6237573DC4ED693D49102C42FFC88510500799"
        println("\nprivatekey given : $privateKey")
        println("publicKeyExpected: $publicKeyExpected")


//        // routine with bouncy castle
//        println("\nGenerate PublicKey from PrivateKey with BouncyCastle")
//        val spec: ECNamedCurveParameterSpec = ECNamedCurveTable.getParameterSpec("secp256k1") // this ec curve is used for bitcoin operations
//        val pointQ: org.bouncycastle.math.ec.ECPoint = spec.getG().multiply(BigInteger(1, ch.qos.logback.core.encoder.ByteArrayUtil.hexStringToByteArray(privateKey)))
//        val publickKeyByte = pointQ.getEncoded(false)
//        val publicKeyBc: String = byteArrayToHexString(publickKeyByte)
//        println("publicKeyExpected: $publicKeyExpected")
//        println("publicKey BC     : $publicKeyBc")
//        println("publicKeys match : " + publicKeyBc.contentEquals(publicKeyExpected))

        // regeneration of ECPublicKey with java native starts here
        println("\nGenerate PublicKey from PrivateKey with Java native routines")
        // the preset "303E.." only works for elliptic curve secp256k1
        // see answer by user dave_thompson_085
        // https://stackoverflow.com/questions/48832170/generate-ec-public-key-from-byte-array-private-key-in-native-java-7

        val privateKeyFull = "303E020100301006072A8648CE3D020106052B8104000A042730250201010420" + privateKey
        val privateKeyFullByte: ByteArray = hexStringToByteArray(privateKeyFull)


        println("privateKey full  : $privateKeyFull")
        val keyFactory = KeyFactory.getInstance("EC")
        val privateKeyNative: PrivateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privateKeyFullByte))
        val ecPrivateKeyNative = privateKeyNative as ECPrivateKey

        val ecPublicKeyNative = getPublicKey(ecPrivateKeyNative)
        val ecPublicKeyNativeByte = ecPublicKeyNative!!.encoded

        val testPubKey = keyFactory.generatePublic(X509EncodedKeySpec(ecPublicKeyNativeByte)) as ECPublicKey
        val equal = ecPublicKeyNativeByte.contentEquals(testPubKey.encoded)

        val publicKeyNativeFull: String = byteArrayToHexString(ecPublicKeyNativeByte)
        val publicKeyNativeHeader = publicKeyNativeFull.substring(0, 46)
        val publicKeyNativeKey = publicKeyNativeFull.substring(46, 176)

        println("ecPublicKeyFull  : $publicKeyNativeFull")
        println("ecPublicKeyHeader: $publicKeyNativeHeader")
        println("ecPublicKeyKey   : $publicKeyNativeKey")
        println("publicKeyExpected: $publicKeyExpected")
        println("publicKeys match : " + publicKeyNativeKey.contentEquals(publicKeyExpected))


        // encrypt
        val encryptCipher: Cipher = Cipher.getInstance("RSA")
        encryptCipher.init(Cipher.ENCRYPT_MODE, ecPublicKeyNative)
        val cipherText: ByteArray = encryptCipher.doFinal(cryptoText.toByteArray())

        // decrypt
        val decryptCipher = Cipher.getInstance("RSA");
        decryptCipher.init(Cipher.DECRYPT_MODE, ecPrivateKeyNative);

        val outputBytes = decryptCipher.doFinal(cipherText)
        println("Crypto round passed: ${String(outputBytes) == cryptoText}")
    }
}
