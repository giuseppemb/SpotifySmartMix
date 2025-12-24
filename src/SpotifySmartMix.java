import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.OutputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.Base64;

public class SpotifySmartMix {

    private static final String CLIENT_ID = ""; //your client id
    private static final String CLIENT_SECRET = ""; //your client secret
    private static final String REDIRECT_URI = ""; //your redirect uri
    private static final int PORT = 8080;

    private static final String SCOPES = String.join(" ",
            "playlist-modify-private",
            "user-top-read",
            "user-read-recently-played"
    );

    private static String ACCESS_TOKEN;

    public static void main(String[] args) throws Exception {

        ACCESS_TOKEN = authenticate();

        List<String> smartMix = fetchSmartMix();
        if (smartMix.isEmpty()) {
            System.out.println("No tracks found. Playlist not created.");
            return;
        }

        Playlist playlist = createPlaylist();
        addTracksToPlaylist(playlist.id, smartMix);

        System.out.println("🎧 Your SmartMix playlist is ready:");
        System.out.println(playlist.url);
    }


    /**
     Authenticates the user via Spotify OAuth and returns an access token.
     */
    private static String authenticate() throws Exception {

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        final String[] tokenJson = new String[1];

        server.createContext("/callback", exchange -> {
            String code = parseQuery(exchange.getRequestURI().getQuery()).get("code");
            String response;
            if (code == null) {
                response = "Authentication failed.";
            } else {
                response = "Login successful. You can close this window.";
                try {
                    tokenJson[0] = fetchAccessToken(code);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                server.stop(0);
            }

            exchange.sendResponseHeaders(200, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        server.start();

        String authUrl =
                "https://accounts.spotify.com/authorize" +
                        "?response_type=code" +
                        "&client_id=" + CLIENT_ID +
                        "&scope=" + URLEncoder.encode(SCOPES, StandardCharsets.UTF_8) +
                        "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8);

        Desktop.getDesktop().browse(new URI(authUrl));

        while (tokenJson[0] == null) Thread.sleep(200);

        return extract(tokenJson[0], "\"access_token\":\"", "\"");
    }


    /**
     Requests an access token from Spotify using the authorization code.
     @param code Authorization code received from the Spotify callback
     @return Raw JSON response containing the access token and metadata
     @throws Exception
     */
    private static String fetchAccessToken(String code) throws Exception {

        String body =
                "grant_type=authorization_code" +
                        "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) +
                        "&redirect_uri=" + URLEncoder.encode(REDIRECT_URI, StandardCharsets.UTF_8);

        String auth = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes());

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://accounts.spotify.com/api/token"))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .body();
    }


    /**
     Fetches the user's recently played and top tracks from Spotify
     and builds a deduplicated SmartMix in priority order.
     @return List of Spotify track URIs for the SmartMix
     */
    private static List<String> fetchSmartMix() throws Exception {

        HttpClient client = HttpClient.newHttpClient();
        LinkedHashSet<String> mix = new LinkedHashSet<>();

        extractUris(get(client,
                "https://api.spotify.com/v1/me/player/recently-played?limit=30"), mix);

        extractUris(get(client,
                "https://api.spotify.com/v1/me/top/tracks?limit=30&time_range=short_term"), mix);

        return new ArrayList<>(mix);
    }


    private static Playlist createPlaylist() throws Exception {

        HttpClient client = HttpClient.newHttpClient();
        String me = get(client, "https://api.spotify.com/v1/me");
        String userId = extract(me, "\"id\":\"", "\"");

        String body = """
                {
                  "name": "SmartMix with Java c: ",
                  "description": "automatically generated SmartMix",
                  "public": false
                }
                """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.spotify.com/v1/users/" + userId + "/playlists"))
                .header("Authorization", "Bearer " + ACCESS_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        String response = client.send(request, HttpResponse.BodyHandlers.ofString()).body();

        String id = extract(response, "\"id\":\"", "\"");
        return new Playlist(id, "https://open.spotify.com/playlist/" + id);
    }


    private static void addTracksToPlaylist(String playlistId, List<String> uris)
            throws Exception {

        if (uris == null || uris.isEmpty()) {
            throw new RuntimeException("SmartMix is empty – no tracks to add.");
        }

        HttpClient client = HttpClient.newHttpClient();

        String jsonBody = buildUrisJson(uris);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                        "https://api.spotify.com/v1/playlists/" + playlistId + "/tracks"))
                .header("Authorization", "Bearer " + ACCESS_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response =
                client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new RuntimeException(
                    "Failed to add tracks. Status: " +
                            response.statusCode() + " Body: " + response.body());
        }
    }


    //helper functions follow

    private static String buildUrisJson(List<String> uris) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"uris\":[");

        for (int i = 0; i < uris.size(); i++) {
            sb.append("\"").append(uris.get(i)).append("\"");
            if (i < uris.size() - 1) sb.append(",");
        }

        sb.append("]}");
        return sb.toString();
    }


    private static String get(HttpClient client, String url) throws Exception {
        return client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + ACCESS_TOKEN)
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        ).body();
    }

    private static void extractUris(String json, Set<String> out) {
        int i = 0;
        while ((i = json.indexOf("\"uri\":\"spotify:track:", i)) != -1) {
            int start = i + 7;
            int end = json.indexOf("\"", start);
            out.add(json.substring(start, end));
            i = end;
        }
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null) return map;
        for (String p : query.split("&")) {
            String[] kv = p.split("=");
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private static String extract(String text, String start, String end) {
        int s = text.indexOf(start);
        if (s == -1) return null;
        s += start.length();
        return text.substring(s, text.indexOf(end, s));
    }

    private record Playlist(String id, String url) {}
}
