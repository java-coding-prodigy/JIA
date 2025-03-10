package io.github.taz03.jia;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.github.taz03.jia.requests.InstagramRequest;
import io.github.taz03.jia.requests.accounts.LoginRequest;
import io.github.taz03.jia.requests.qe.QeSyncRequest;
import io.github.taz03.jia.responses.InstagramResponse;
import io.github.taz03.jia.responses.accounts.LoginResponse;

import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InstagramClient {
    private static final Logger log = LoggerFactory.getLogger(InstagramClient.class);

    private final String username, password;
    private String authorization;
    private long pk;
    private HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Makes an Intagram clinet, not logined.
     *
     * @param username The instagram username
     * @param password The instagram password corresponding to the username provided
     */
    public InstagramClient(String username, String password) {
        this(username, password, null);
    }

    /**
     * Makes an Instagram client logined.
     *
     * @param username      The instagram username
     * @param password      The instagram password corresponding to the username provided
     * @param authorization The authorization token to corresponding to the username and password provided
     */
    public InstagramClient(String username, String password, String authorization) {
        this.username = username;
        this.password = password;
        this.authorization = authorization;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getAuthorization() {
        return authorization;
    }

    public long getPk() {
        return pk;
    }

    public void setAuthorization(String authorization) {
        this.authorization = authorization;
    }

    /**
     * Sends a login request for the username and password
     */
    public LoginResponse login() throws Exception {
        HttpResponse<Void> qeResponse = httpClient.send(new QeSyncRequest().formRequest(this), BodyHandlers.discarding());

        HttpHeaders headers = qeResponse.headers();
        String encryptionId = headers.firstValue("ig-set-password-encryption-key-id").get();
        String encryptionKey = headers.firstValue("ig-set-password-encryption-pub-key").get();

        String encryptedPassword = encryptPassword(password, encryptionId, encryptionKey);

        LoginResponse response = sendRequest(new LoginRequest(this.username, encryptedPassword)).get();
        this.pk = response.getUser().getProfile().getPk();

        return response;
    }

    /**
     * Sends a request from this account.
     *
     * @param <T>     Response type from instagram
     * @param request Request to send
     * @return        CompletableFuture of response, throws {@link UncheckedIOException} if json parsing fails 
     */
    public <T extends InstagramResponse> CompletableFuture<T> sendRequest(InstagramRequest<T> request) {
        return httpClient.sendAsync(request.formRequest(this), BodyHandlers.ofString())
            .thenApply(response -> {
                response.headers().firstValue("ig-set-authorization").ifPresent(this::setAuthorization);
                try {
					return request.parseResponse(response.body());
				} catch (JsonProcessingException e) {
                    log.debug("Json parsing failed, json: {}, class: {}", response.body(), request.getResponseType());
                    throw new UncheckedIOException(e);
				}
            }
        );
    }

    /**
     * Encrypts a password using a combination of RSA and AES encryption algorithms.
     *
     * @param password      The password to be encrypted
     * @param encryptionId  An identifier for the encryption
     * @param encryptionKey The public key for RSA encryption
     * @return              A string representation of the encrypted password, including the encryption metadata
     * @throws Exception    If an error occurs during encryption
     */
    private static String encryptPassword(String password, String encryptionId, String encryptionKey) throws Exception {
        final int versionNumber = 4; // the encryption format being used, probably to future-proof the encryption process
        final int ivSize = 12;       // the size of the initialization vector used for AES
        final int aesKeySize = 32;
        final int gcmTagSize = 16;   // authentication tag size that is generated by the AES/GCM encryption

        byte[] randomKey = new byte[aesKeySize];  // random key used for AES encryption
        byte[] iv = new byte[ivSize];  // initialization vector for AES encryption
        SecureRandom sran = new SecureRandom();
        sran.nextBytes(randomKey);
        sran.nextBytes(iv);
        String time = String.valueOf(System.currentTimeMillis() / 1000);  // current timestamp

        // Decode and sanitize the public key
        String decodedPublicKey = new String(Base64.getDecoder().decode(encryptionKey), StandardCharsets.UTF_8)
                        .replaceAll("-(.*)-|\n", "");

        // Encrypt random key with RSA
        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1PADDING");
        rsaCipher.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA")
                .generatePublic(
                        new X509EncodedKeySpec(Base64.getDecoder().decode(decodedPublicKey))));
        byte[] randKeyEncrypted = rsaCipher.doFinal(randomKey);

        // Encrypt password with AES
        Cipher aesGcmCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesGcmCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(randomKey, "AES"),
                new GCMParameterSpec(128, iv));
        aesGcmCipher.updateAAD(time.getBytes());
        byte[] encryptedPassword = aesGcmCipher.doFinal(password.getBytes());

        // Write all data to final byte array
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(Integer.valueOf(1).byteValue());
        out.write(Integer.valueOf(encryptionId).byteValue());
        out.write(iv);
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putChar((char) randKeyEncrypted.length).array());
        out.write(randKeyEncrypted);
        out.write(Arrays.copyOfRange(encryptedPassword, encryptedPassword.length - gcmTagSize, encryptedPassword.length));
        out.write(Arrays.copyOfRange(encryptedPassword, 0, encryptedPassword.length - gcmTagSize));

        // Return final string formatted as "#PWD_INSTAGRAM:%s:%s:%s", with version, timestamp and encrypted data
        return String.format("#PWD_INSTAGRAM:%s:%s:%s", versionNumber, time, Base64.getEncoder().encodeToString(out.toByteArray()));
    }
}
