package org.sigil.app;

import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.PGPEncryptedDataGenerator;
import org.bouncycastle.openpgp.PGPEncryptedDataList;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPLiteralData;
import org.bouncycastle.openpgp.PGPLiteralDataGenerator;
import org.bouncycastle.openpgp.PGPObjectFactory;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyEncryptedData;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSecretKey;
import org.bouncycastle.openpgp.PGPSecretKeyRing;
import org.bouncycastle.openpgp.PGPSecretKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.operator.PBESecretKeyEncryptor;
import org.bouncycastle.openpgp.operator.PGPDigestCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPDigestCalculatorProviderBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcaPGPKeyPair;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyDecryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePBESecretKeyEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePGPDataEncryptorBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyDataDecryptorFactoryBuilder;
import org.bouncycastle.openpgp.operator.jcajce.JcePublicKeyKeyEncryptionMethodGenerator;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/** All OpenPGP work happens here, on the phone. Only ciphertext ever leaves it. */
public final class Pgp {

    private Pgp() {}

    /** A user's key pair, plus the passphrase while the session is unlocked. */
    public static final class Identity {
        final PGPSecretKeyRing secret;
        final PGPPublicKeyRing pub;
        final char[] pass;

        Identity(PGPSecretKeyRing secret, PGPPublicKeyRing pub, char[] pass) {
            this.secret = secret;
            this.pub = pub;
            this.pass = pass;
        }

        public String fingerprint() {
            return hex(pub.getPublicKey().getFingerprint());
        }

        public PGPPublicKey selfEncryptionKey() {
            return encryptionKeyOf(pub);
        }

        char[] passphrase() {
            return pass;
        }

        public String publicArmor() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (ArmoredOutputStream a = new ArmoredOutputStream(b)) {
                pub.encode(a);
            }
            return b.toString("UTF-8");
        }

        public String secretArmor() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (ArmoredOutputStream a = new ArmoredOutputStream(b)) {
                secret.encode(a);
            }
            return b.toString("UTF-8");
        }
    }

    /** Creates a new RSA-3072 key pair (signing master key plus encryption subkey). */
    public static Identity generate(String name, char[] passphrase) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(3072);
        Date now = new Date();

        PGPKeyPair master = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_GENERAL, kpg.generateKeyPair(), now);
        PGPKeyPair sub = new JcaPGPKeyPair(PublicKeyAlgorithmTags.RSA_ENCRYPT, kpg.generateKeyPair(), now);

        PGPDigestCalculator sha1 = new JcaPGPDigestCalculatorProviderBuilder().build().get(HashAlgorithmTags.SHA1);

        PGPSignatureSubpacketGenerator masterSub = new PGPSignatureSubpacketGenerator();
        masterSub.setKeyFlags(false, KeyFlags.CERTIFY_OTHER | KeyFlags.SIGN_DATA);

        PGPSignatureSubpacketGenerator encSub = new PGPSignatureSubpacketGenerator();
        encSub.setKeyFlags(false, KeyFlags.ENCRYPT_COMMS | KeyFlags.ENCRYPT_STORAGE);

        PBESecretKeyEncryptor encryptor = new JcePBESecretKeyEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256, sha1)
                .setProvider("BC")
                .build(passphrase);

        PGPKeyRingGenerator gen = new PGPKeyRingGenerator(
                PGPSignature.POSITIVE_CERTIFICATION,
                master,
                name,
                sha1,
                masterSub.generate(),
                null,
                new JcaPGPContentSignerBuilder(master.getPublicKey().getAlgorithm(), HashAlgorithmTags.SHA256),
                encryptor);
        gen.addSubKey(sub, encSub.generate(), null);

        return new Identity(gen.generateSecretKeyRing(), gen.generatePublicKeyRing(), passphrase.clone());
    }

    /** Loads an imported key pair and checks the passphrase by unlocking the private key. */
    public static Identity load(String secretArmor, String publicArmor, char[] passphrase) throws Exception {
        PGPSecretKeyRingCollection sc = new PGPSecretKeyRingCollection(
                PGPUtil.getDecoderStream(in(secretArmor)), new JcaKeyFingerprintCalculator());
        PGPSecretKeyRing secret = (PGPSecretKeyRing) sc.getKeyRings().next();

        PGPPublicKeyRingCollection pc = new PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(in(publicArmor)), new JcaKeyFingerprintCalculator());
        PGPPublicKeyRing pub = (PGPPublicKeyRing) pc.getKeyRings().next();

        // Throws if the passphrase is wrong.
        secret.getSecretKey().extractPrivateKey(decryptor(passphrase));
        return new Identity(secret, pub, passphrase.clone());
    }

    /** Encrypts for every recipient. The sender's own key is included so they can read their sent messages. */
    public static String encrypt(String plain, List<PGPPublicKey> recipients) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ArmoredOutputStream armor = new ArmoredOutputStream(raw);

        PGPEncryptedDataGenerator gen = new PGPEncryptedDataGenerator(
                new JcePGPDataEncryptorBuilder(SymmetricKeyAlgorithmTags.AES_256)
                        .setWithIntegrityPacket(true)
                        .setProvider("BC"));
        for (PGPPublicKey k : recipients) {
            gen.addMethod(new JcePublicKeyKeyEncryptionMethodGenerator(k).setProvider("BC"));
        }

        OutputStream encOut = gen.open(armor, new byte[1 << 16]);
        PGPLiteralDataGenerator lit = new PGPLiteralDataGenerator();
        byte[] data = plain.getBytes(StandardCharsets.UTF_8);
        OutputStream litOut = lit.open(encOut, PGPLiteralData.BINARY, "", (long) data.length, new Date());
        litOut.write(data);
        litOut.close();
        encOut.close();
        armor.close();
        return raw.toString("UTF-8");
    }

    public static String decrypt(String armored, Identity id) throws Exception {
        InputStream in = PGPUtil.getDecoderStream(in(armored));
        PGPObjectFactory factory = new PGPObjectFactory(in, new JcaKeyFingerprintCalculator());
        Object o = factory.nextObject();
        if (!(o instanceof PGPEncryptedDataList)) o = factory.nextObject();
        if (!(o instanceof PGPEncryptedDataList)) throw new Exception("Not an encrypted message");

        PGPEncryptedDataList list = (PGPEncryptedDataList) o;
        for (Iterator<?> it = list.getEncryptedDataObjects(); it.hasNext(); ) {
            PGPPublicKeyEncryptedData pked = (PGPPublicKeyEncryptedData) it.next();
            PGPSecretKey sk = id.secret.getSecretKey(pked.getKeyID());
            if (sk == null) continue;

            PGPPrivateKey priv = sk.extractPrivateKey(decryptor(id.passphrase()));
            InputStream clear = pked.getDataStream(
                    new JcePublicKeyDataDecryptorFactoryBuilder().setProvider("BC").build(priv));

            PGPObjectFactory lf = new PGPObjectFactory(clear, new JcaKeyFingerprintCalculator());
            Object ld = lf.nextObject();
            if (ld instanceof PGPLiteralData) {
                InputStream ls = ((PGPLiteralData) ld).getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = ls.read(buf)) != -1) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        }
        throw new Exception("This message is not addressed to your key");
    }

    /** Reads the encryption key from a recipient's armored public key. */
    public static PGPPublicKey encryptionKeyFrom(String armoredPublic) throws Exception {
        PGPPublicKeyRingCollection c = new PGPPublicKeyRingCollection(
                PGPUtil.getDecoderStream(in(armoredPublic)), new JcaKeyFingerprintCalculator());
        for (Iterator<?> it = c.getKeyRings(); it.hasNext(); ) {
            PGPPublicKey k = encryptionKeyOf((PGPPublicKeyRing) it.next());
            if (k != null) return k;
        }
        throw new Exception("This user's key cannot encrypt");
    }

    /** Finds the encryption-capable subkey of a public key ring. */
    static PGPPublicKey encryptionKeyOf(PGPPublicKeyRing ring) {
        for (Iterator<?> it = ring.getPublicKeys(); it.hasNext(); ) {
            PGPPublicKey k = (PGPPublicKey) it.next();
            if (k.isEncryptionKey()) return k;
        }
        return null;
    }

    private static org.bouncycastle.openpgp.operator.PBESecretKeyDecryptor decryptor(char[] pass) throws Exception {
        return new JcePBESecretKeyDecryptorBuilder(new JcaPGPDigestCalculatorProviderBuilder().build())
                .setProvider("BC")
                .build(pass);
    }

    private static InputStream in(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02X", b & 0xff));
        return sb.toString();
    }
}
