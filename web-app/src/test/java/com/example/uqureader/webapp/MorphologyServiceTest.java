package com.example.uqureader.webapp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MorphologyServiceTest {

    private final Gson gson = new Gson();
    private MorphologyService service;

    @BeforeAll
    void setUp() {
        service = new MorphologyService();
    }

    @AfterAll
    void tearDown() {
        service.close();
    }

    @Test
    void tokenAnalysisMatchesPython() throws Exception {
        String token = "Комедия";
        JsonObject expected = runPythonToken(token);
        JsonObject actual = service.analyzeToken(token);
        Assertions.assertEquals(expected, actual);
    }

    @Test
    void textAnalysisMatchesPython() throws Exception {
        String text = "Комедия пәрдәдә";
        JsonObject expected = runPythonText(text);
        JsonObject actual = service.analyzeText(text);
        Assertions.assertEquals(expected, actual);
    }

    @Test
    void httpEndpointReturnsSameResult() throws Exception {
        String text = "Комедия пәрдәдә";
        JsonObject expected = runPythonText(text);
        WebMorphologyApplication application = new WebMorphologyApplication(service);
        HttpServer server = application.start(0);
        try {
            int port = server.getAddress().getPort();
            URL url = new URL("http://localhost:" + port + "/api/text/");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = connection.getOutputStream()) {
                os.write(gson.toJson(Map.of("text", text)).getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            Assertions.assertEquals(200, status);
            String body = readAll(connection.getInputStream());
            JsonObject actual = gson.fromJson(body, JsonObject.class);
            Assertions.assertEquals(expected, actual);
        } finally {
            server.stop(0);
        }
    }

    private JsonObject runPythonToken(String token) throws Exception {
        String code = String.join("\n",
                "import inspect",
                "from collections import namedtuple",
                "if not hasattr(inspect, 'getargspec'):",
                "    _ArgSpec = namedtuple('ArgSpec', 'args varargs keywords defaults')",
                "    def _getargspec(func):",
                "        spec = inspect.getfullargspec(func)",
                "        return _ArgSpec(spec.args, spec.varargs, spec.varkw, spec.defaults)",
                "    inspect.getargspec = _getargspec",
                "import json",
                "import sys",
                "from py_tat_morphan.morphan import Morphan",
                "from py_tat_morphan import __version__",
                "morphan = Morphan()",
                "token = sys.argv[1]",
                "result = morphan.analyse(token)",
                "print(json.dumps({'token': token, 'tag': result, 'morphan_version': __version__, 'format': 1}, ensure_ascii=False))"
        );
        ProcessBuilder builder = new ProcessBuilder(resolvePythonExecutable(), "-c", code, token);
        builder.environment().put("PYTHONIOENCODING", "UTF-8");
        Process process = builder.start();
        String stdout = readAll(process.getInputStream());
        String stderr = readAll(process.getErrorStream());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Python exited with " + exit + ": " + stderr);
        }
        return gson.fromJson(stdout.trim(), JsonObject.class);
    }

    private JsonObject runPythonText(String text) throws Exception {
        String code = String.join("\n",
                "import inspect",
                "from collections import namedtuple",
                "if not hasattr(inspect, 'getargspec'):",
                "    _ArgSpec = namedtuple('ArgSpec', 'args varargs keywords defaults')",
                "    def _getargspec(func):",
                "        spec = inspect.getfullargspec(func)",
                "        return _ArgSpec(spec.args, spec.varargs, spec.varkw, spec.defaults)",
                "    inspect.getargspec = _getargspec",
                "import json",
                "import sys",
                "from py_tat_morphan.morphan import Morphan",
                "from py_tat_morphan import __version__",
                "morphan = Morphan()",
                "data = sys.stdin.read()",
                "tokens_count, unique_tokens_count, sentences_count, sentences = morphan.analyse_text(data)",
                "payload = {",
                "    'tokens_count': tokens_count,",
                "    'unique_tokens_count': unique_tokens_count,",
                "    'sentenes_count': sentences_count,",
                "    'sentences': sentences,",
                "    'morphan_version': __version__,",
                "    'format': 1",
                "}",
                "print(json.dumps(payload, ensure_ascii=False))"
        );
        ProcessBuilder builder = new ProcessBuilder(resolvePythonExecutable(), "-c", code);
        builder.environment().put("PYTHONIOENCODING", "UTF-8");
        Process process = builder.start();
        try (OutputStreamWriter writer = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            writer.write(text);
        }
        String stdout = readAll(process.getInputStream());
        String stderr = readAll(process.getErrorStream());
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("Python exited with " + exit + ": " + stderr);
        }
        return gson.fromJson(stdout.trim(), JsonObject.class);
    }

    private static String resolvePythonExecutable() {
        String property = System.getProperty("py.tat.morphan.python");
        if (property != null && !property.isEmpty()) {
            return property;
        }
        String env = System.getenv("PY_TAT_MORPHAN_PYTHON");
        if (env != null && !env.isEmpty()) {
            return env;
        }
        return "python3";
    }

    private String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }
}
