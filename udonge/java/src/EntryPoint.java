package io.sevcator.udonge;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;

import java.lang.reflect.Field;
import java.security.KeyPairGeneratorSpi;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;

public final class EntryPoint {
    private static final String ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17";

    public static void init() {
        try {
            spoofBuildFields();
            spoofProvider();
        } catch (Throwable ignored) {}
    }

    private static void spoofBuildFields() {
        try {
            setField(Build.class, "IS_DEBUGGABLE", false);
            setField(Build.class, "TAGS", "release-keys");
            setField(Build.class, "TYPE", "user");
        } catch (Throwable ignored) {}
    }

    private static void setField(Class<?> cls, String name, Object value) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            try {
                Field modifiers = Field.class.getDeclaredField("accessFlags");
                modifiers.setAccessible(true);
                modifiers.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
            } catch (NoSuchFieldException e) {
                try {
                    Field modifiers = Field.class.getDeclaredField("modifiers");
                    modifiers.setAccessible(true);
                    modifiers.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
                } catch (NoSuchFieldException ignored) {}
            }
            f.set(null, value);
        } catch (Throwable ignored) {}
    }

    private static void spoofProvider() throws Exception {
        Provider keystoreProvider = Security.getProvider("AndroidKeyStore");
        if (keystoreProvider == null) {
            return;
        }

        Provider spoofed = new SpoofProvider(keystoreProvider);
        Security.removeProvider("AndroidKeyStore");
        Security.insertProviderAt(spoofed, 1);
    }

    static final class SpoofProvider extends Provider {
        private final Provider real;

        SpoofProvider(Provider real) {
            super(real.getName(), real.getVersion(), real.getInfo());
            this.real = real;
            putAll(real);
        }

        @Override
        public synchronized Service getService(String type, String algorithm) {
            Service realService = real.getService(type, algorithm);
            if (realService == null) return null;

            if ("KeyStore".equals(type) && "AndroidKeyStore".equals(algorithm)) {
                return new HookedService(this, type, algorithm,
                        realService.getClassName(), null, null, realService);
            }

            if ("KeyPairGenerator".equals(type)) {
                return new HookedKpgService(this, type, algorithm,
                        realService.getClassName(), null, null, realService);
            }

            return realService;
        }
    }

    static final class HookedService extends Provider.Service {
        private final Provider.Service real;

        HookedService(Provider provider, String type, String algorithm,
                      String className, java.util.List<String> aliases,
                      java.util.Map<String, String> attributes,
                      Provider.Service realService) {
            super(provider, type, algorithm, className, aliases, attributes);
            this.real = realService;
        }

        @Override
        public Object newInstance(Object constructorParameter)
                throws java.security.NoSuchAlgorithmException {
            try {
                Object spi = real.newInstance(constructorParameter);
                if (spi instanceof KeyStoreSpi) {
                    return new HookedKeyStoreSpi((KeyStoreSpi) spi);
                }
                return spi;
            } catch (Exception e) {
                throw new java.security.NoSuchAlgorithmException(e);
            }
        }
    }

    static final class HookedKpgService extends Provider.Service {
        private final Provider.Service real;

        HookedKpgService(Provider provider, String type, String algorithm,
                         String className, java.util.List<String> aliases,
                         java.util.Map<String, String> attributes,
                         Provider.Service realService) {
            super(provider, type, algorithm, className, aliases, attributes);
            this.real = realService;
        }

        @Override
        public Object newInstance(Object constructorParameter)
                throws java.security.NoSuchAlgorithmException {
            try {
                Object spi = real.newInstance(constructorParameter);
                if (spi instanceof KeyPairGeneratorSpi) {
                    return new HookedKeyPairGeneratorSpi((KeyPairGeneratorSpi) spi);
                }
                return spi;
            } catch (Exception e) {
                throw new java.security.NoSuchAlgorithmException(e);
            }
        }
    }

    static final class HookedKeyPairGeneratorSpi extends KeyPairGeneratorSpi {
        private final KeyPairGeneratorSpi delegate;

        HookedKeyPairGeneratorSpi(KeyPairGeneratorSpi delegate) {
            this.delegate = delegate;
        }

        @Override
        public void initialize(int keysize, java.security.SecureRandom random) {
            delegate.initialize(keysize, random);
        }

        @Override
        public void initialize(AlgorithmParameterSpec params, java.security.SecureRandom random)
                throws java.security.InvalidAlgorithmParameterException {
            if (params instanceof KeyGenParameterSpec) {
                KeyGenParameterSpec spec = (KeyGenParameterSpec) params;
                if (spec.getAttestationChallenge() != null) {
                    params = stripAttestationChallenge(spec);
                }
            }
            delegate.initialize(params, random);
        }

        @Override
        public java.security.KeyPair generateKeyPair() {
            return delegate.generateKeyPair();
        }

        private static AlgorithmParameterSpec stripAttestationChallenge(KeyGenParameterSpec spec) {
            try {
                KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
                        spec.getKeystoreAlias(), spec.getPurposes());

                if (spec.getKeySize() != -1) builder.setKeySize(spec.getKeySize());
                if (spec.getAlgorithmParameterSpec() != null)
                    builder.setAlgorithmParameterSpec(spec.getAlgorithmParameterSpec());
                if (spec.getCertificateSubject() != null)
                    builder.setCertificateSubject(spec.getCertificateSubject());
                if (spec.getCertificateSerialNumber() != null)
                    builder.setCertificateSerialNumber(spec.getCertificateSerialNumber());
                if (spec.getCertificateNotBefore() != null)
                    builder.setCertificateNotBefore(spec.getCertificateNotBefore());
                if (spec.getCertificateNotAfter() != null)
                    builder.setCertificateNotAfter(spec.getCertificateNotAfter());

                builder.setDigests(spec.getDigests());

                if (spec.getEncryptionPaddings() != null && spec.getEncryptionPaddings().length > 0)
                    builder.setEncryptionPaddings(spec.getEncryptionPaddings());
                if (spec.getSignaturePaddings() != null && spec.getSignaturePaddings().length > 0)
                    builder.setSignaturePaddings(spec.getSignaturePaddings());
                if (spec.getBlockModes() != null && spec.getBlockModes().length > 0)
                    builder.setBlockModes(spec.getBlockModes());

                builder.setRandomizedEncryptionRequired(spec.isRandomizedEncryptionRequired());
                builder.setUserAuthenticationRequired(spec.isUserAuthenticationRequired());
                builder.setUserAuthenticationValidityDurationSeconds(
                        spec.getUserAuthenticationValidityDurationSeconds());

                // Do NOT set attestation challenge — this is the whole point

                return builder.build();
            } catch (Throwable t) {
                return spec;
            }
        }
    }

    static final class HookedKeyStoreSpi extends KeyStoreSpi {
        private final KeyStoreSpi delegate;

        HookedKeyStoreSpi(KeyStoreSpi delegate) {
            this.delegate = delegate;
        }

        @Override
        public Certificate[] engineGetCertificateChain(String alias) {
            Certificate[] chain = delegate.engineGetCertificateChain(alias);
            if (chain != null && chain.length > 0 && chain[0] instanceof X509Certificate) {
                X509Certificate leaf = (X509Certificate) chain[0];
                if (leaf.getExtensionValue(ATTESTATION_OID) != null) {
                    throw new UnsupportedOperationException();
                }
            }
            return chain;
        }

        @Override
        public java.security.Key engineGetKey(String alias, char[] password)
                throws java.security.NoSuchAlgorithmException,
                java.security.UnrecoverableKeyException {
            return delegate.engineGetKey(alias, password);
        }

        @Override
        public java.util.Date engineGetCreationDate(String alias) {
            return delegate.engineGetCreationDate(alias);
        }

        @Override
        public void engineSetKeyEntry(String alias, java.security.Key key,
                                      char[] password, Certificate[] chain)
                throws KeyStoreException {
            delegate.engineSetKeyEntry(alias, key, password, chain);
        }

        @Override
        public void engineSetKeyEntry(String alias, byte[] key,
                                      Certificate[] chain)
                throws KeyStoreException {
            delegate.engineSetKeyEntry(alias, key, chain);
        }

        @Override
        public void engineSetCertificateEntry(String alias, Certificate cert)
                throws KeyStoreException {
            delegate.engineSetCertificateEntry(alias, cert);
        }

        @Override
        public void engineDeleteEntry(String alias) throws KeyStoreException {
            delegate.engineDeleteEntry(alias);
        }

        @Override
        public java.util.Enumeration<String> engineAliases() {
            return delegate.engineAliases();
        }

        @Override
        public boolean engineContainsAlias(String alias) {
            return delegate.engineContainsAlias(alias);
        }

        @Override
        public int engineSize() {
            return delegate.engineSize();
        }

        @Override
        public boolean engineIsKeyEntry(String alias) {
            return delegate.engineIsKeyEntry(alias);
        }

        @Override
        public boolean engineIsCertificateEntry(String alias) {
            return delegate.engineIsCertificateEntry(alias);
        }

        @Override
        public String engineGetCertificateAlias(Certificate cert) {
            return delegate.engineGetCertificateAlias(cert);
        }

        @Override
        public void engineStore(java.io.OutputStream stream, char[] password)
                throws java.io.IOException,
                java.security.NoSuchAlgorithmException,
                java.security.cert.CertificateException {
            delegate.engineStore(stream, password);
        }

        @Override
        public void engineLoad(java.io.InputStream stream, char[] password)
                throws java.io.IOException,
                java.security.NoSuchAlgorithmException,
                java.security.cert.CertificateException {
            delegate.engineLoad(stream, password);
        }

        @Override
        public Certificate engineGetCertificate(String alias) {
            return delegate.engineGetCertificate(alias);
        }
    }
}
