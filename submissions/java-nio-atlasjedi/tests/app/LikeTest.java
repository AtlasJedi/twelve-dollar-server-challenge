package app;

import java.nio.file.*;

/** Task 10: POST /posts/:id/like — 201 first, 200 repeat, 404 missing post, auth before id, like_count read-back. */
public class LikeTest {
    static void check(boolean ok, String msg) {
        if (!ok) throw new AssertionError(msg);
    }

    static void eq(String name, String exp, String act) {
        check(exp.equals(act), name + ": expected <" + exp + "> got <" + act + ">");
    }

    static int count(String json) {
        int i = json.indexOf("\"like_count\":") + 13;
        int j = i;
        while (Character.isDigit(json.charAt(j))) j++;
        return Integer.parseInt(json.substring(i, j));
    }

    public static void main(String[] args) throws Exception {
        Path seed = Path.of("../../seed/feed.db").toRealPath();
        Path tmp = Files.createTempFile("feed-like", ".db");
        Files.copy(seed, tmp, StandardCopyOption.REPLACE_EXISTING);
        Server server = Main.start(tmp.toString(), JwtTest.SECRET, "127.0.0.1", 0);
        CreateTest.port = server.port();
        Thread loop = new Thread(server::run, "loop");
        loop.setDaemon(true);
        loop.start();

        long now = System.currentTimeMillis() / 1000;
        String auth = "Bearer " + JwtTest.sign(JwtTest.HDR, JwtTest.payload("\"1\"", "\"golden_ember_1\"", now + 3600));

        // fresh post
        String created = CreateTest.send("POST", "/posts", auth, "{\"body\":\"like me\"}");
        String id = created.substring(created.indexOf("\"id\":") + 5, created.indexOf(",\"body\""));
        String p = "/posts/" + id + "/like";
        eq("first like", "201|{\"liked\":true,\"already_liked\":false,\"post_id\":" + id + "}", CreateTest.send("POST", p, auth, null));
        eq("repeat like", "200|{\"liked\":true,\"already_liked\":true,\"post_id\":" + id + "}", CreateTest.send("POST", p, auth, null));
        eq("repeat again", "200|{\"liked\":true,\"already_liked\":true,\"post_id\":" + id + "}", CreateTest.send("POST", p, auth, null));
        check(count(CreateTest.send("GET", "/posts/" + id, null, null)) == 1, "like_count 1");

        // errors: auth -> id -> existence
        eq("missing post", "404|{\"error\":\"post not found\"}", CreateTest.send("POST", "/posts/999999999/like", auth, null));
        eq("bad id with token", "400|{\"error\":\"invalid post id\"}", CreateTest.send("POST", "/posts/abc/like", auth, null));
        eq("bad id no token", "401|{\"error\":\"missing bearer token\"}", CreateTest.send("POST", "/posts/abc/like", null, null));
        eq("missing post no token", "401|{\"error\":\"missing bearer token\"}", CreateTest.send("POST", "/posts/999999999/like", null, null));
        // server still healthy after the constraint failure
        eq("missing post again", "404|{\"error\":\"post not found\"}", CreateTest.send("POST", "/posts/999999999/like", auth, null));
        check(count(CreateTest.send("GET", "/posts/" + id, null, null)) == 1, "like_count still 1");

        // seeded post 1: golden + 1
        int golden = count(CreateTest.send("GET", "/posts/1", null, null));
        String r = CreateTest.send("POST", "/posts/1/like", auth, null);
        eq("like post 1", "201|{\"liked\":true,\"already_liked\":false,\"post_id\":1}", r);
        check(count(CreateTest.send("GET", "/posts/1", null, null)) == golden + 1, "post 1 like_count golden+1");
        System.out.println("LikeTest OK");
    }
}
