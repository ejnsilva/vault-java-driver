package com.bettercloud.vault;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertTrue;

/**
 * Unit tests for the <code>VaultConfig</code> settings loader.
 */
public class VaultConfigTests {

    /**
     * <p>The code used by <code>VaultConfig</code> to load environment variables is encapsulated within an inner
     * class, so that a mock version of that environment loader can be used by unit tests.</p>
     *
     * <p>This mock implementation of <code>VaultConfig.EnvironmentLoader</code> allows unit tests to declare values
     * that should be returned for a given environment variable name.  The actual environment is never used.</p>
     *
     * <p>The <code>VAULT_TOKEN</code> variable gets special treatment.  If a value cannot be found in the environment,
     * then {@link com.bettercloud.vault.VaultConfig.EnvironmentLoader} looks for a <code>.vault-token</code> file in
     * the user's home directory.  So this mock has a second constructor which allows you to pass a directory path,
     * to serve as a mock "home directory" for testing.</p>
     */
    class MockEnvironmentLoader extends VaultConfig.EnvironmentLoader {
        final Map<String, String> overrides;
        final String mockHomeDirectory;

        public MockEnvironmentLoader() {
            overrides = new HashMap<String, String>();
            mockHomeDirectory = "";
        }

        public MockEnvironmentLoader(final String mockHomeDirectory) {
            overrides = new HashMap<String, String>();
            this.mockHomeDirectory = mockHomeDirectory;
        }

        /**
         * Declare a variable and value to be available in the mock "environment".  This method may be called
         * repeatedly, to populate multiple variables.  This method should be called prior to passing the object
         * instance to a <code>VaultConfig</code> constructor, or calling the <code>build()</code> method on that class.
         *
         * @param name Mock environment variable name
         * @param value Mock environment variable value
         */
        public void override(final String name, final String value) {
            this.overrides.put(name, value);
        }

        @Override
        public String loadVariable(final String name) {
            String value = null;
            if ("VAULT_TOKEN".equals(name)) {
                if (overrides.containsKey("VAULT_TOKEN")) {
                    value = overrides.get("VAULT_TOKEN");
                } else {
                    try {
                        final byte[] bytes = Files.readAllBytes(Paths.get(mockHomeDirectory).resolve(".vault-token"));
                        value = new String(bytes, "UTF-8").trim();
                    } catch (IOException e) {
                    }
                }
            } else {
                value = overrides.get(name);
            }
            return value;
        }

    }

    /**
     * Test creating a new <code>VaultConfig</code> via its constructor, passing address and token values and ensuring
     * that they're later accessible.
     *
     * @throws VaultException
     */
    @Test
    public void testConfigConstructor() throws VaultException {
        final VaultConfig config = new VaultConfig("address", "token");
        assertEquals("address", config.getAddress());
        assertEquals("token", config.getToken());
    }

    /**
     * Test creating a new <code>VaultConfig</code> via its constructor, deliberately passing null address and token
     * values so it's forced to fetch them from environment variables.
     *
     * @throws VaultException
     */
    @Test
    public void testConfigConstructor_LoadFromEnv() throws VaultException {
        final MockEnvironmentLoader mock = new MockEnvironmentLoader();
        // Required
        mock.override("VAULT_ADDR", "http://127.0.0.1:8200");
        mock.override("VAULT_TOKEN", "c24e2469-298a-6c64-6a71-5b47c9ba459a");
        // Optional
        mock.override("VAULT_PROXY_ADDRESS", "localhost");
        mock.override("VAULT_PROXY_PORT", "80");
        mock.override("VAULT_PROXY_USERNAME", "scott");
        mock.override("VAULT_PROXY_PASSWORD", "tiger");
        mock.override("VAULT_SSL_VERIFY", "true");
        mock.override("VAULT_OPEN_TIMEOUT", "30");
        mock.override("VAULT_READ_TIMEOUT", "30");

        final VaultConfig config = new VaultConfig(null, null, mock);
        assertEquals("http://127.0.0.1:8200", config.getAddress());
        assertEquals("c24e2469-298a-6c64-6a71-5b47c9ba459a", config.getToken());
        assertTrue(config.isSslVerify());
        assertTrue(30 == config.getOpenTimeout());
        assertTrue(30 == config.getReadTimeout());
    }

    /**
     * Test creating a new <code>VaultConfig</code> via its constructor, passing null address and token values AND
     * having them unavailable in the environment variables too.  This should cause initialization failure.
     *
     * @throws VaultException
     */
    @Test(expected = VaultException.class)
    public void testConfigConstructor_FailToLoad() throws VaultException {
        new VaultConfig(null);
    }

    /**
     * Test creating a <code>VaultConfig</code> instance via its builder pattern, explicitly specifying address and
     * token values.
     *
     * @throws VaultException
     */
    @Test
    public void testConfigBuilder() throws VaultException {
        final VaultConfig config =
                new VaultConfig()
                        .address("address")
                        .token("token")
                        .build();
        assertEquals("address", config.getAddress());
        assertEquals("token", config.getToken());
    }

    /**
     * Test creating a <code>VaultConfig</code> instance via its builder pattern, forcing it to look to the environment
     * variables for address and token values.
     *
     * @throws VaultException
     */
    @Test
    public void testConfigBuilder_LoadFromEnv() throws VaultException {
        final MockEnvironmentLoader mock = new MockEnvironmentLoader();
        mock.override("VAULT_ADDR", "http://127.0.0.1:8200");
        mock.override("VAULT_TOKEN", "c24e2469-298a-6c64-6a71-5b47c9ba459a");
        mock.override("VAULT_PROXY_ADDRESS", "localhost");
        mock.override("VAULT_PROXY_PORT", "80");
        mock.override("VAULT_PROXY_USERNAME", "scott");
        mock.override("VAULT_PROXY_PASSWORD", "tiger");
        mock.override("VAULT_SSL_VERIFY", "true");
        mock.override("VAULT_OPEN_TIMEOUT", "30");
        mock.override("VAULT_READ_TIMEOUT", "30");

        final VaultConfig config = new VaultConfig()
                .environmentLoader(mock)
                .build();
        assertEquals("http://127.0.0.1:8200", config.getAddress());
        assertEquals("c24e2469-298a-6c64-6a71-5b47c9ba459a", config.getToken());
        assertTrue(config.isSslVerify());
        assertTrue(30 == config.getOpenTimeout());
        assertTrue(30 == config.getReadTimeout());
    }

    @Test
    public void testConfigBuilder_LoadFromEnv_SslCert() throws IOException, VaultException {
        final String tempDirectoryPath = System.getProperty("java.io.tmpdir");
        final String pemPath = tempDirectoryPath + File.separator + "cert.pem";
        try (
                final InputStream input = this.getClass().getResourceAsStream("/cert.pem");
                final FileOutputStream output = new FileOutputStream(pemPath)
        ) {
            int nextChar;
            while ( (nextChar = input.read()) != -1 ) {
                output.write( (char) nextChar );
            }
        }

        final MockEnvironmentLoader mock = new MockEnvironmentLoader();
        mock.override("VAULT_ADDR", "http://127.0.0.1:8200");
        mock.override("VAULT_SSL_CERT", pemPath);
        final VaultConfig config = new VaultConfig()
                .environmentLoader(mock)
                .build();

        final String expected = "-----BEGIN CERTIFICATE-----MIIDhjCCAm6gAwIBAgIES40FSTANBgkqhkiG9w0BAQsFADBrMQswCQYDVQQGEwJVUzERMA8GA1UECBMIQW55c3RhdGUxEDAOBgNVBAcTB0FueXRvd24xETAPBgNVBAoTCFRlc3QgT3JnMRAwDgYDVQQLEwdUZXN0IE9VMRIwEAYDVQQDEwlUZXN0IFVzZXIwHhcNMTYwMjE2MTcwNDQ3WhcNMTYwNTE2MTcwNDQ3WjBrMQswCQYDVQQGEwJVUzERMA8GA1UECBMIQW55c3RhdGUxEDAOBgNVBAcTB0FueXRvd24xETAPBgNVBAoTCFRlc3QgT3JnMRAwDgYDVQQLEwdUZXN0IE9VMRIwEAYDVQQDEwlUZXN0IFVzZXIwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCHNAd93WjoDl7EYddxqpAd9FGoyvFA0900tmLJWmD3YPXhkOkXO38E//tS9KkXD39tDsDwHxw53iF1SmzgrHvzJzQvGjR5rvp7KjMhv/wlpED2E4FR/q2WigoXVtzpOwc4fk4PizBZV4fkSOtiQA0LEoQochw8wp7OI1tzE5iISKggD0N9EOJUzwQIcAgkAdaYEP9Fd2YMgTJAiHSakOgQowKQQGmIbKg0YWici9tiojwNCuNlcp1kBEUi4odO6BxRs8RKk6McvHCu1+2SSlxctGGU8kFKsF92/sULxvHAOovYspKdBJfw2f088Hnfw3jSgaWWQNB+oilVsfECx1BPAgMBAAGjMjAwMA8GA1UdEQQIMAaHBH8AAAEwHQYDVR0OBBYEFHuppZEESxlasbK5aq4LvF/IhtseMA0GCSqGSIb3DQEBCwUAA4IBAQBK9g8sWk6jCPekk2VjK6aKYIs4BB79xsaj41hdjoMwVRSelSpsmJE34/Vflqy+SBrf59czvk/UqJIYSrHRx7M0hpfIChmqqNEj5NKY+MFBuOTt4r/Wv3tbBTf2CMs4hLnkevhleNLxJhAjvh7r52U+uE8l6O11dsQRVXOSGnwdnvInVTs1ilxdTQh680DEU0q26P3o36N3Oxxgls2ZC3ExnLJnOofhj01l6cYhI06RtFPzJtv5sICCkYGMDKSIsWUndmurZjLAjsAKPT/RePeqyW0dKY5ZjtC+YAg5i3O0DLhERsDZECIp56oqsYxATuoHVzbjorM2ua2pUcuIR0p3-----END CERTIFICATE-----";
        final String actual = config.getSslPemUTF8().replaceAll(System.lineSeparator(), "");
        assertEquals(actual, expected);
    }

    @Test(expected = VaultException.class)
    public void testConfigBuilder_LoadFromEnv_SslCert_NotFound() throws VaultException {
        final MockEnvironmentLoader mock = new MockEnvironmentLoader();
        mock.override("VAULT_ADDR", "http://127.0.0.1:8200");
        mock.override("VAULT_SSL_CERT", "doesnt-exist.pem");
        new VaultConfig()
                .environmentLoader(mock)
                .build();
    }

    /**
     * Test creating a <code>VaultConfig</code> instance via its builder pattern, with no address no token values
     * passed OR available in the environment.  This should cause initialization failure.
     *
     * @throws VaultException
     */
    @Test(expected = VaultException.class)
    public void testConfigBuilder_FailToLoad() throws VaultException {
        new VaultConfig().build();
    }

    @Test
    public void testConfigBuilder_LoadTokenFromHomedir() throws IOException, VaultException {
        final String mockHomeDirectory = System.getProperty("java.io.tmpdir") + File.separatorChar + UUID.randomUUID().toString();
        assertTrue(new File(mockHomeDirectory).mkdirs());
        final File mockTokenFile = new File(mockHomeDirectory + File.separatorChar + ".vault-token");
        assertTrue(mockTokenFile.createNewFile());
        final PrintWriter out = new PrintWriter(mockTokenFile, "UTF-8");
        out.println("d24e2469-298a-6c64-6a71-5b47c9ba459a");
        out.close();

        final MockEnvironmentLoader mock = new MockEnvironmentLoader(mockHomeDirectory);
        mock.override("VAULT_ADDR", "http://127.0.0.1:8200");
        mock.override("VAULT_PROXY_ADDRESS", "localhost");
        mock.override("VAULT_PROXY_PORT", "80");
        mock.override("VAULT_PROXY_USERNAME", "scott");
        mock.override("VAULT_PROXY_PASSWORD", "tiger");
        mock.override("VAULT_SSL_VERIFY", "true");
        mock.override("VAULT_OPEN_TIMEOUT", "30");
        mock.override("VAULT_READ_TIMEOUT", "30");

        final VaultConfig config = new VaultConfig()
                .environmentLoader(mock)
                .build();
        assertEquals("http://127.0.0.1:8200", config.getAddress());
        assertEquals("d24e2469-298a-6c64-6a71-5b47c9ba459a", config.getToken());
        assertTrue(config.isSslVerify());
        assertTrue(30 == config.getOpenTimeout());
        assertTrue(30 == config.getReadTimeout());

        assertTrue(mockTokenFile.delete());
        assertTrue(new File(mockHomeDirectory).delete());
    }

}
