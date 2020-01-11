package com.oblador.keychain.cipherStorage;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.security.keystore.UserNotAuthenticatedException;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.facebook.react.bridge.ReactApplicationContext;

import com.facebook.react.bridge.ReactContext;
import com.oblador.keychain.SecurityLevel;
import com.oblador.keychain.components.BiometricPromptHelper;
import com.oblador.keychain.exceptions.CryptoFailedException;
import com.oblador.keychain.exceptions.KeyStoreAccessException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

@RequiresApi(Build.VERSION_CODES.M)
public class CipherStorageKeystoreRSAECB extends CipherStorageKeystoreMarshmallowBase implements BiometricPromptHelper.BiometricAuthenticationResult {
    private static final String CIPHER_STORAGE_NAME = "KeystoreRSAECB";
    private static final String KEYSTORE_TYPE = "AndroidKeyStore";
    private static final String ENCRYPTION_ALGORITHM = KeyProperties.KEY_ALGORITHM_RSA;
    private static final String ENCRYPTION_BLOCK_MODE = KeyProperties.BLOCK_MODE_ECB;
    private static final String ENCRYPTION_PADDING = KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1;
    private static final String ENCRYPTION_TRANSFORMATION =
            ENCRYPTION_ALGORITHM + "/" +
                    ENCRYPTION_BLOCK_MODE + "/" +
                    ENCRYPTION_PADDING;
    private static final int ENCRYPTION_KEY_SIZE = 3072;

    private class CipherDecryptionParams {
        private final DecryptionResultHandler resultHandler;
        private final Key key;
        private final byte[] username;
        private final byte[] password;

        private CipherDecryptionParams(DecryptionResultHandler handler, Key key, byte[] username, byte[] password) {
            this.resultHandler = handler;
            this.key = key;
            this.username = username;
            this.password = password;
        }
    }

    private CipherDecryptionParams mDecryptParams;

    @Override
    public void onError(int errorCode, @Nullable CharSequence errString) {
        if (mDecryptParams != null && mDecryptParams.resultHandler != null) {
            mDecryptParams.resultHandler.onDecrypt(null, errString != null ? errString.toString() : "Impossible to authenticate");
            mDecryptParams = null;
        }
    }

    @Override
    public void onSuccess() {
        if (mDecryptParams != null && mDecryptParams.resultHandler != null) {
            try {
                String decryptedUsername = decryptBytes(mDecryptParams.key, mDecryptParams.username);
                String decryptedPassword = decryptBytes(mDecryptParams.key, mDecryptParams.password);
                mDecryptParams.resultHandler.onDecrypt(new DecryptionResult(decryptedUsername, decryptedPassword, SecurityLevel.ANY), null);
            } catch (Exception e) {
                mDecryptParams.resultHandler.onDecrypt(null, e.getMessage());
            }
            mDecryptParams = null;
        }
    }

    // returns true if the key was generated successfully
    @TargetApi(Build.VERSION_CODES.M)
    protected Key generateKey(KeyGenParameterSpec spec) throws NoSuchProviderException,
            NoSuchAlgorithmException, InvalidAlgorithmParameterException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(ENCRYPTION_ALGORITHM, KEYSTORE_TYPE);
        generator.initialize(spec);
        return generator.generateKeyPair().getPrivate();
    }

    protected KeyInfo getKeyInfo(Key key) throws NoSuchProviderException, NoSuchAlgorithmException, InvalidKeySpecException {
        KeyFactory factory = KeyFactory.getInstance(key.getAlgorithm(), KEYSTORE_TYPE);
        KeyInfo keyInfo = factory.getKeySpec(key, KeyInfo.class);
        return keyInfo;
    }

    @Override
    public String getCipherStorageName() {
        return CIPHER_STORAGE_NAME;
    }

    @Override
    public boolean getCipherBiometrySupported() {
        return true;
    }

    @Override
    public int getMinSupportedApiLevel() {
        return Build.VERSION_CODES.M;
    }

    @Override
    public EncryptionResult encrypt(@NonNull String service, @NonNull String username, @NonNull String password, SecurityLevel level) throws CryptoFailedException {
        service = getDefaultServiceIfEmpty(service);

        try {
            KeyStore keyStore = getKeyStoreAndLoad();

            if (!keyStore.containsAlias(service)) {
                generateKeyAndStoreUnderAlias(service, level);
            }

            KeyFactory keyFactory = KeyFactory.getInstance(ENCRYPTION_ALGORITHM);
            PublicKey publicKey = keyStore.getCertificate(service).getPublicKey();
            KeySpec spec = new X509EncodedKeySpec(publicKey.getEncoded());
            Key key = keyFactory.generatePublic(spec);

            byte[] encryptedUsername = encryptString(key, service, username);
            byte[] encryptedPassword = encryptString(key, service, password);

            return new EncryptionResult(encryptedUsername, encryptedPassword, this);
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException | NoSuchProviderException e) {
            throw new CryptoFailedException("Could not encrypt data for service " + service, e);
        } catch (KeyStoreException | KeyStoreAccessException e) {
            throw new CryptoFailedException("Could not access Keystore for service " + service, e);
        } catch (Exception e) {
            throw new CryptoFailedException("Unknown error: " + e.getMessage(), e);
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    protected KeyGenParameterSpec.Builder getKeyGenSpecBuilder(String service) {
        return new KeyGenParameterSpec.Builder(
                service,
                KeyProperties.PURPOSE_DECRYPT | KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(ENCRYPTION_BLOCK_MODE)
                .setEncryptionPaddings(ENCRYPTION_PADDING)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(1)
                .setKeySize(ENCRYPTION_KEY_SIZE);
    }

    @Override
    public void decrypt(@NonNull DecryptionResultHandler decryptionResultHandler, @NonNull String service, @NonNull byte[] username, @NonNull byte[] password, FragmentActivity activity) throws CryptoFailedException, KeyPermanentlyInvalidatedException {
        service = getDefaultServiceIfEmpty(service);

        BiometricPromptHelper mBiometricHelper = new BiometricPromptHelper(activity);
        KeyStore keyStore;
        Key key;

        try {
            keyStore = getKeyStoreAndLoad();
            key = keyStore.getKey(service, null);
        } catch (KeyStoreException | UnrecoverableKeyException | NoSuchAlgorithmException e) {
            throw new CryptoFailedException("Could not get key from Keystore", e);
        } catch (KeyStoreAccessException e) {
            throw new CryptoFailedException("Could not access Keystore", e);
        } catch (Exception e) {
            throw new CryptoFailedException("Unknown error: " + e.getMessage(), e);
        }

        String decryptedUsername;
        String decryptedPassword;
        try {
            // try to get a Cipher, if exception is thrown, authentication is needed
            decryptedUsername = decryptBytes(key, username);
            decryptedPassword = decryptBytes(key, password);
        } catch (UserNotAuthenticatedException e) {
            mDecryptParams = new CipherDecryptionParams(decryptionResultHandler, key, username, password);
            if (!mBiometricHelper.canStartFingerprintAuthentication()) {
                throw new CryptoFailedException("Could not start fingerprint Authentication");
            }
            try {
                // The Cipher is locked, we will decrypt once fingerprint is recognised.
                mBiometricHelper.startFingerprintAuthentication(this);
            } catch (Exception e1) {
                e1.printStackTrace();
                throw new CryptoFailedException("Could not start fingerprint Authentication", e1);
            }
            return;
        }

        // The Cipher is unlocked, we can decrypt straight away.
        decryptionResultHandler.onDecrypt(new DecryptionResult(decryptedUsername, decryptedPassword, SecurityLevel.ANY), null);
    }

    private byte[] encryptString(Key key, String service, String value) throws CryptoFailedException {
        try {
            Cipher cipher = Cipher.getInstance(ENCRYPTION_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            // encrypt the value using a CipherOutputStream
            CipherOutputStream cipherOutputStream = new CipherOutputStream(outputStream, cipher);
            cipherOutputStream.write(value.getBytes("UTF-8"));
            cipherOutputStream.close();
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new CryptoFailedException("Could not encrypt value for service " + service, e);
        }
    }

    private Cipher getDecryptionCipher(Key key) throws CryptoFailedException, UserNotAuthenticatedException, KeyPermanentlyInvalidatedException {
        try {
            Cipher cipher = Cipher.getInstance(ENCRYPTION_TRANSFORMATION);
            // read the initialization vector from the beginning of the stream
            cipher.init(Cipher.DECRYPT_MODE, key);

            return cipher;
        } catch (UserNotAuthenticatedException | KeyPermanentlyInvalidatedException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoFailedException("Could not generate cipher", e);
        }
    }

    private String decryptBytes(Key key, byte[] bytes) throws CryptoFailedException, UserNotAuthenticatedException, KeyPermanentlyInvalidatedException {
        try {
            Cipher cipher = getDecryptionCipher(key);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
            // decrypt the bytes using a CipherInputStream
            CipherInputStream cipherInputStream = new CipherInputStream(
                    inputStream, cipher);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            while (true) {
                int n = cipherInputStream.read(buffer, 0, buffer.length);
                if (n <= 0) {
                    break;
                }
                output.write(buffer, 0, n);
            }
            return new String(output.toByteArray(), Charset.forName("UTF-8"));
        } catch (IOException e) {
            throw new CryptoFailedException("Could not decrypt bytes", e);
        }
    }
}
