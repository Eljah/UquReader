package com.example.uqureader.webapp.cli;

import com.example.uqureader.webapp.MorphologyException;
import com.example.uqureader.webapp.morphology.RemoteMorphologyClient;
import com.example.uqureader.webapp.morphology.RemoteMorphologyClient.WordMarkup;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Command line entry point that annotates raw Tatar texts using the Tugantel
 * morphology service.
 */
public final class TatarMorphologyAnnotator {

    private final RemoteMorphologyClient client;
    private final PrintStream out;
    private final PrintStream err;

    public TatarMorphologyAnnotator(RemoteMorphologyClient client, PrintStream out, PrintStream err) {
        this.client = Objects.requireNonNull(client, "client");
        this.out = Objects.requireNonNull(out, "out");
        this.err = Objects.requireNonNull(err, "err");
    }

    public static void main(String[] args) {
        RemoteMorphologyClient client = new RemoteMorphologyClient();
        TatarMorphologyAnnotator annotator = new TatarMorphologyAnnotator(client, System.out, System.err);
        int exitCode = annotator.run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    int run(String[] args) {
        if (args.length == 0) {
            printUsage();
            return 1;
        }

        List<Path> files = new ArrayList<>(args.length);
        for (String arg : args) {
            Path path = Path.of(arg);
            if (!Files.exists(path)) {
                err.printf("Файл не найден: %s%n", path);
                return 2;
            }
            if (!Files.isRegularFile(path)) {
                err.printf("Не является файлом: %s%n", path);
                return 2;
            }
            files.add(path);
        }

        int failures = 0;
        for (Path file : files) {
            try {
                annotateFile(file);
            } catch (IOException ex) {
                failures++;
                err.printf("Не удалось прочитать файл %s: %s%n", file, ex.getMessage());
            } catch (MorphologyException ex) {
                failures++;
                err.printf("Ошибка морфологического анализа файла %s: %s%n", file, ex.getMessage());
            }
        }
        if (failures > 0) {
            err.printf("Завершено с ошибками (%d файлов не обработано).%n", failures);
            return 3;
        }
        return 0;
    }

    private void annotateFile(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            out.printf("# %s — пустой файл%n%n", file);
            return;
        }

        out.printf("# Файл: %s%n", file);
        List<WordMarkup> markup = client.analyzeText(content);
        for (WordMarkup token : markup) {
            out.printf("%s\t%s%n", token.word(), String.join(" | ", token.analyses()));
        }
        out.println();
    }

    private void printUsage() {
        err.println("Использование: java -cp web-app-<версия>.jar com.example.uqureader.webapp.cli.TatarMorphologyAnnotator <файл> [<файл> ...]");
        err.println("Каждый указанный файл будет отправлен на сервис Tugantel для морфологической разметки.");
    }
}
