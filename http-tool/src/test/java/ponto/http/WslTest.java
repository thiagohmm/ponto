package ponto.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WslTest {

    @Test
    void mapsLocalhost() {
        assertEquals(
                "http://10.255.255.254:8080/api",
                Wsl.mapLocalhostToWindows("http://localhost:8080/api", "10.255.255.254"));
        assertEquals(
                "https://10.1.1.1/v1?x=1",
                Wsl.mapLocalhostToWindows("https://127.0.0.1/v1?x=1", "10.1.1.1"));
    }

    @Test
    void leavesRemoteHosts() {
        assertEquals(
                "https://api.example.com/x",
                Wsl.mapLocalhostToWindows("https://api.example.com/x", "10.1.1.1"));
    }

    @Test
    void nullSafe() {
        assertNull(Wsl.mapLocalhostToWindows(null, "1.1.1.1"));
        assertEquals("http://localhost/", Wsl.mapLocalhostToWindows("http://localhost/", null));
    }
}
