package com.example.uqureader.webapp.dictionary;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Utility responsible for downloading the Apertium tat-rus dictionary and transforming it into a
 * local SQLite table that can be used by the web application.
 */
public final class TatRusDictionaryImporter {

    private static final URI DICTIONARY_URI = URI.create(
            "https://raw.githubusercontent.com/apertium/apertium-tat-rus/master/"
                    + "apertium-tat-rus.tat-rus.dix");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Imports the latest dictionary from GitHub and writes it into the target SQLite database. The
     * method returns the number of entries inserted.
     *
     * @param databasePath path to the SQLite file; parent directories will be created if required
     * @return number of dictionary rows stored in the table
     * @throws IOException  when download or parsing fails
     * @throws SQLException when the database cannot be updated
     */
    public int importLatest(Path databasePath) throws IOException, SQLException {
        Objects.requireNonNull(databasePath, "databasePath");
        byte[] payload = downloadDictionary();
        List<Entry> entries = parseDictionary(new ByteArrayInputStream(payload));
        if (entries.isEmpty()) {
            throw new IOException("Dictionary contained no lexical entries");
        }
        return writeToDatabase(databasePath, entries);
    }

    private byte[] downloadDictionary() throws IOException {
        URL url = DICTIONARY_URI.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("Accept", "application/xml");
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            InputStream errorStream = connection.getErrorStream();
            String message = status + " " + connection.getResponseMessage();
            if (errorStream != null) {
                try (InputStream stream = errorStream) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    copy(stream, buffer);
                    message += ": " + new String(buffer.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            connection.disconnect();
            throw new IOException("Dictionary download failed with HTTP status " + message);
        }
        try (InputStream input = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            copy(input, output);
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private List<Entry> parseDictionary(InputStream stream) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setIgnoringComments(false);
            factory.setIgnoringElementContentWhitespace(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new InputSource(stream));

            Element root = document.getDocumentElement();
            if (root == null) {
                return Collections.emptyList();
            }

            List<Entry> entries = new ArrayList<>();
            NodeList sectionNodes = root.getElementsByTagName("section");
            for (int i = 0; i < sectionNodes.getLength(); i++) {
                Node node = sectionNodes.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element section = (Element) node;
                String sectionId = section.getAttribute("id");
                Node child = section.getFirstChild();
                while (child != null) {
                    if (child.getNodeType() == Node.ELEMENT_NODE
                            && "e".equals(((Element) child).getTagName())) {
                        Entry entry = parseEntry((Element) child, sectionId);
                        if (entry != null) {
                            entries.add(entry);
                        }
                    }
                    child = child.getNextSibling();
                }
            }
            return entries;
        } catch (ParserConfigurationException | SAXException ex) {
            throw new IOException("Failed to parse dictionary XML", ex);
        }
    }

    private Entry parseEntry(Element element, String sectionId) {
        Element lElement = findFirstDescendant(element, "l");
        Element rElement = findFirstDescendant(element, "r");
        if (lElement == null || rElement == null) {
            return null;
        }

        String tatLemma = normaliseText(extractText(lElement));
        String rusLemma = normaliseText(extractText(rElement));
        if (tatLemma.isEmpty() && rusLemma.isEmpty()) {
            return null;
        }

        String tatSurface = lElement.hasAttribute("c") ? lElement.getAttribute("c") : tatLemma;
        tatSurface = normaliseText(tatSurface);
        if (tatSurface.isEmpty()) {
            tatSurface = tatLemma;
        }

        String direction = element.hasAttribute("r") ? element.getAttribute("r") : "LR";
        if (direction == null || direction.trim().isEmpty()) {
            direction = "LR";
        }

        List<String> tatTags = collectTags(lElement);
        List<String> rusTags = collectTags(rElement);
        List<String> paradigms = collectParadigms(element);
        String comment = extractTrailingComment(element);

        return new Entry(
                tatLemma,
                tatSurface,
                tatTags,
                rusLemma,
                rusTags,
                paradigms,
                sectionId == null ? "" : sectionId,
                direction,
                comment
        );
    }

    private Element findFirstDescendant(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                return (Element) node;
            }
        }
        return null;
    }

    private String extractText(Element element) {
        StringBuilder builder = new StringBuilder();
        appendText(element, builder);
        return builder.toString();
    }

    private void appendText(Node node, StringBuilder builder) {
        Node child = node.getFirstChild();
        while (child != null) {
            short type = child.getNodeType();
            if (type == Node.TEXT_NODE) {
                builder.append(child.getNodeValue());
            } else if (type == Node.ELEMENT_NODE) {
                Element element = (Element) child;
                String tagName = element.getTagName();
                if ("s".equals(tagName) || "par".equals(tagName)) {
                    // skip morphology tags and paradigm markers from text output
                } else if ("b".equals(tagName)) {
                    builder.append(' ');
                } else {
                    appendText(element, builder);
                }
            }
            child = child.getNextSibling();
        }
    }

    private List<String> collectTags(Element element) {
        NodeList nodes = element.getElementsByTagName("s");
        List<String> tags = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element tagElement = (Element) node;
            if (tagElement.hasAttribute("n")) {
                String value = tagElement.getAttribute("n");
                if (!value.isEmpty()) {
                    tags.add(value);
                }
            }
        }
        return immutableList(tags);
    }

    private List<String> collectParadigms(Element element) {
        NodeList nodes = element.getElementsByTagName("par");
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element parElement = (Element) node;
            if (parElement.hasAttribute("n")) {
                String value = parElement.getAttribute("n");
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return immutableList(new ArrayList<>(result));
    }

    private String extractTrailingComment(Element element) {
        Node sibling = element.getNextSibling();
        while (sibling != null) {
            if (sibling.getNodeType() == Node.COMMENT_NODE) {
                Comment comment = (Comment) sibling;
                return normaliseWhitespace(comment.getData());
            }
            if (sibling.getNodeType() == Node.TEXT_NODE) {
                String text = sibling.getTextContent();
                if (text != null && text.trim().isEmpty()) {
                    sibling = sibling.getNextSibling();
                    continue;
                }
            }
            break;
        }
        return null;
    }

    private String normaliseText(String value) {
        if (value == null) {
            return "";
        }
        String result = value.replace('\u00A0', ' ');
        result = normaliseWhitespace(result);
        if (result.isEmpty()) {
            return "";
        }
        return Normalizer.normalize(result, Normalizer.Form.NFC);
    }

    private String normaliseWhitespace(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.replaceAll("\\s+", " ");
    }

    private int writeToDatabase(Path databasePath, List<Entry> entries) throws IOException, SQLException {
        Path parent = databasePath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath();
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            initialiseDatabase(connection);
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM tat_rus_dictionary");
                }
                String sql = "INSERT INTO tat_rus_dictionary (tat_lemma, tat_surface, tat_tags, "
                        + "rus_lemma, rus_tags, paradigm, section, direction, comment) VALUES (?,?,?,?,?,?,?,?,?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    for (Entry entry : entries) {
                        statement.setString(1, entry.tatLemma());
                        statement.setString(2, entry.tatSurface());
                        statement.setString(3, toJson(entry.tatTags()));
                        statement.setString(4, entry.rusLemma());
                        statement.setString(5, toJson(entry.rusTags()));
                        statement.setString(6, toJson(entry.paradigms()));
                        statement.setString(7, entry.section());
                        statement.setString(8, entry.direction());
                        if (entry.comment() == null || entry.comment().isEmpty()) {
                            statement.setNull(9, java.sql.Types.VARCHAR);
                        } else {
                            statement.setString(9, entry.comment());
                        }
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException ex) {
                connection.rollback();
                throw ex;
            }
            return entries.size();
        }
    }

    private void initialiseDatabase(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS tat_rus_dictionary ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "tat_lemma TEXT NOT NULL,"
                    + "tat_surface TEXT NOT NULL,"
                    + "tat_tags TEXT NOT NULL,"
                    + "rus_lemma TEXT NOT NULL,"
                    + "rus_tags TEXT NOT NULL,"
                    + "paradigm TEXT NOT NULL,"
                    + "section TEXT NOT NULL,"
                    + "direction TEXT NOT NULL,"
                    + "comment TEXT"
                    + ")");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tat_rus_lemma ON tat_rus_dictionary(tat_lemma)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_tat_rus_section ON tat_rus_dictionary(section)");
        }
    }

    private void copy(InputStream input, ByteArrayOutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private String toJson(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return GSON.toJson(values);
    }

    private List<String> immutableList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static final class Entry {
        private final String tatLemma;
        private final String tatSurface;
        private final List<String> tatTags;
        private final String rusLemma;
        private final List<String> rusTags;
        private final List<String> paradigms;
        private final String section;
        private final String direction;
        private final String comment;

        Entry(String tatLemma,
              String tatSurface,
              List<String> tatTags,
              String rusLemma,
              List<String> rusTags,
              List<String> paradigms,
              String section,
              String direction,
              String comment) {
            this.tatLemma = tatLemma;
            this.tatSurface = tatSurface;
            this.tatTags = tatTags;
            this.rusLemma = rusLemma;
            this.rusTags = rusTags;
            this.paradigms = paradigms;
            this.section = section;
            this.direction = direction;
            this.comment = comment;
        }

        String tatLemma() {
            return tatLemma;
        }

        String tatSurface() {
            return tatSurface;
        }

        List<String> tatTags() {
            return tatTags;
        }

        String rusLemma() {
            return rusLemma;
        }

        List<String> rusTags() {
            return rusTags;
        }

        List<String> paradigms() {
            return paradigms;
        }

        String section() {
            return section;
        }

        String direction() {
            return direction;
        }

        String comment() {
            return comment;
        }
    }
}
