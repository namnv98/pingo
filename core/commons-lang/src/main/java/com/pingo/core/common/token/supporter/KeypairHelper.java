package com.pingo.core.common.token.supporter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Objects;

import com.pingo.core.common.token.NdlTokenException;
import lombok.SneakyThrows;

public class KeypairHelper {

    @SneakyThrows
    public static KeyPair generateKeyPair(AlgorithmName algorithmName, int keySize) {
        var keyGen = KeyPairGenerator.getInstance(algorithmName.getValue());
        keyGen.initialize(keySize);
        return keyGen.generateKeyPair();
    }

    private static final String readKey(InputStream inputStream) {
        String key;
        try {
            key = new String(inputStream.readAllBytes());
        } catch (IOException e) {
            throw new NdlTokenException("Cannot read key from input stream", e);
        }
        return key.replaceAll("\\-+[a-zA-Z 0-9]+\\-+", "").trim().replace("\n", "");
    }

    public static RSAPublicKey loadPublicKey(String resourceName) {
        try (var inputStream = KeypairHelper.class.getClassLoader().getResourceAsStream(resourceName)) {
            return loadPublicKey(inputStream);
        } catch (IOException e) {
            throw new NdlTokenException(
                    "Error while loading private key from resource: " + resourceName, e);
        }
    }

    public static final RSAPublicKey loadPublicKey(File file) {
        try (var inputStream = new FileInputStream(file)) {
            return loadPublicKey(inputStream);
        } catch (IOException e) {
            throw new NdlTokenException("Error while loading public key from file: " + file, e);
        }
    }

    public static final RSAPublicKey loadPublicKey(InputStream inputStream) {
        return readPublicKey(readKey(inputStream));
    }

    public static RSAPublicKey readPublicKey(String key) {
        if (Objects.isNull(key)) {
            return null;
        }
        try {
            var encoded = Base64.getDecoder().decode(key);
            var kf = KeyFactory.getInstance("RSA");
            return (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new NdlTokenException("Error while loading public key from input stream", e);
        }
    }

    public static RSAPrivateKey loadPrivateKey(String resourceName) {
        try (var inputStream = KeypairHelper.class.getClassLoader().getResourceAsStream(resourceName)) {
            return loadPrivateKey(inputStream);
        } catch (IOException e) {
            throw new NdlTokenException(
                    "Error while loading private key from resource: " + resourceName, e);
        }
    }

    public static RSAPrivateKey loadPrivateKey(File file) {
        try (var inputStream = new FileInputStream(file)) {
            return loadPrivateKey(inputStream);
        } catch (IOException e) {
            throw new NdlTokenException("Error while loading private key from file: " + file, e);
        }
    }

    public static RSAPrivateKey loadPrivateKey(InputStream inputStream) {
        return readPrivateKey(readKey(inputStream));
    }

    public static RSAPrivateKey readPrivateKey(String key) {
        if (Objects.isNull(key)) {
            return null;
        }
        try {
            var spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(key));
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception e) {
            throw new NdlTokenException("Error while loading private key from input stream", e);
        }
    }
}
