package com.example.uqureader.webapp.morphology.hfst;

import com.example.uqureader.webapp.MorphologyException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure Java implementation of the HFST OL (optimized lookup) transducer reader and analyser.
 * The code mirrors the behaviour of the original Python implementation bundled with the
 * {@code py_tat_morphan} project, but has been translated to Java so it can be embedded in the
 * web application without relying on a Python runtime.
 */
public final class HfstTransducer {

    private static final byte[] HFST_SIGNATURE = new byte[]{'H', 'F', 'S', 'T', 0};

    private static final long TRANSITION_TARGET_TABLE_START = 2147483648L; // 2^31
    private static final long NO_TABLE_INDEX = 4294967295L; // unsigned int max
    private static final int NO_SYMBOL_NUMBER = 65535; // unsigned short max

    private final Alphabet alphabet;
    private final AbstractTransducer transducer;

    private HfstTransducer(Alphabet alphabet, AbstractTransducer transducer) {
        this.alphabet = alphabet;
        this.transducer = transducer;
    }

    public static HfstTransducer read(InputStream stream) {
        Objects.requireNonNull(stream, "stream");
        try {
            Header header = Header.read(stream);
            Alphabet alphabet = Alphabet.read(stream, header.numberOfSymbols);
            AbstractTransducer transducer;
            if (header.weighted) {
                transducer = new WeightedTransducer(stream, header, alphabet);
            } else {
                transducer = new SimpleTransducer(stream, header, alphabet);
            }
            return new HfstTransducer(alphabet, transducer);
        } catch (IOException ex) {
            throw new MorphologyException("Failed to load HFST transducer", ex);
        }
    }

    public List<Analysis> analyze(String input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyList();
        }
        return transducer.analyze(input);
    }

    public record Analysis(String output, double weight) {}

    private static final class Header {
        private final int numberOfSymbols;
        private final int sizeOfTransitionIndexTable;
        private final int sizeOfTransitionTargetTable;
        private final boolean weighted;

        private Header(int numberOfSymbols,
                       int sizeOfTransitionIndexTable,
                       int sizeOfTransitionTargetTable,
                       boolean weighted) {
            this.numberOfSymbols = numberOfSymbols;
            this.sizeOfTransitionIndexTable = sizeOfTransitionIndexTable;
            this.sizeOfTransitionTargetTable = sizeOfTransitionTargetTable;
            this.weighted = weighted;
        }

        static Header read(InputStream stream) throws IOException {
            byte[] prefix = readFully(stream, 5);
            byte[] headerBytes;
            if (matchesSignature(prefix)) {
                byte[] sizeBytes = readFully(stream, 3);
                ByteBuffer tmp = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN);
                int remaining = Short.toUnsignedInt(tmp.getShort());
                if (remaining > 0) {
                    readFully(stream, remaining);
                }
                headerBytes = readFully(stream, 56);
            } else {
                byte[] rest = readFully(stream, 56 - 5);
                headerBytes = new byte[56];
                System.arraycopy(prefix, 0, headerBytes, 0, prefix.length);
                System.arraycopy(rest, 0, headerBytes, prefix.length, rest.length);
            }
            ByteBuffer buffer = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
            buffer.getShort(); // number_of_input_symbols, unused
            int numberOfSymbols = Short.toUnsignedInt(buffer.getShort());
            int sizeOfTransitionIndexTable = buffer.getInt();
            int sizeOfTransitionTargetTable = buffer.getInt();
            buffer.getInt(); // number_of_states
            buffer.getInt(); // number_of_transitions
            boolean weighted = buffer.getInt() != 0;
            buffer.getInt(); // deterministic
            buffer.getInt(); // input_deterministic
            buffer.getInt(); // minimized
            buffer.getInt(); // cyclic
            buffer.getInt(); // has_epsilon_epsilon_transitions
            buffer.getInt(); // has_input_epsilon_transitions
            buffer.getInt(); // has_input_epsilon_cycles
            buffer.getInt(); // has_unweighted_input_epsilon_cycles
            return new Header(numberOfSymbols,
                    sizeOfTransitionIndexTable,
                    sizeOfTransitionTargetTable,
                    weighted);
        }

        private static boolean matchesSignature(byte[] bytes) {
            if (bytes.length != HFST_SIGNATURE.length) {
                return false;
            }
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] != HFST_SIGNATURE[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class Alphabet {
        private final List<String> keyTable;
        private final Map<Integer, FlagDiacriticOperation> flagDiacriticOperations;

        private Alphabet(List<String> keyTable,
                         Map<Integer, FlagDiacriticOperation> flagDiacriticOperations) {
            this.keyTable = keyTable;
            this.flagDiacriticOperations = flagDiacriticOperations;
        }

        static Alphabet read(InputStream stream, int numberOfSymbols) throws IOException {
            List<String> keyTable = new ArrayList<>(numberOfSymbols);
            Map<Integer, FlagDiacriticOperation> operations = new HashMap<>();
            for (int i = 0; i < numberOfSymbols; i++) {
                keyTable.add("");
            }
            for (int i = 0; i < numberOfSymbols; i++) {
                String symbol = readSymbol(stream);
                if (symbol.length() > 4
                        && symbol.charAt(0) == '@'
                        && symbol.charAt(symbol.length() - 1) == '@'
                        && symbol.charAt(2) == '.'
                        && "PNRDCU".indexOf(symbol.charAt(1)) >= 0) {
                    FlagDiacriticOperation operation = parseFlag(symbol.substring(1, symbol.length() - 1));
                    if (operation != null) {
                        operations.put(i, operation);
                        keyTable.set(i, "");
                        continue;
                    }
                }
                keyTable.set(i, symbol);
            }
            if (!keyTable.isEmpty()) {
                keyTable.set(0, "");
            }
            return new Alphabet(Collections.unmodifiableList(keyTable),
                    Collections.unmodifiableMap(operations));
        }

        private static String readSymbol(InputStream stream) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            while (true) {
                int value = stream.read();
                if (value == -1) {
                    throw new IOException("Unexpected end of alphabet section");
                }
                if (value == 0) {
                    break;
                }
                buffer.write(value);
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }

        private static FlagDiacriticOperation parseFlag(String body) {
            String[] parts = body.split("\\.", -1);
            if (parts.length == 2) {
                return new FlagDiacriticOperation(parts[0], parts[1], "");
            }
            if (parts.length == 3) {
                return new FlagDiacriticOperation(parts[0], parts[1], parts[2]);
            }
            return null;
        }
    }

    private abstract static class AbstractTransducer {
        protected final Alphabet alphabet;
        protected final FlagDiacriticStateStack stateStack = new FlagDiacriticStateStack();
        protected final LetterTrie letterTrie;
        protected IndexList outputString = new IndexList();
        protected IndexList inputString = new IndexList();

        protected AbstractTransducer(Alphabet alphabet, LetterTrie letterTrie) {
            this.alphabet = alphabet;
            this.letterTrie = letterTrie;
        }

        abstract List<Analysis> analyze(String input);
    }

    private static final class SimpleTransducer extends AbstractTransducer {

        private final TransitionIndex[] indices;
        private final Transition[] transitions;

        private SimpleTransducer(InputStream stream, Header header, Alphabet alphabet) throws IOException {
            super(alphabet, buildTrie(alphabet));
            this.indices = readIndices(stream, header.sizeOfTransitionIndexTable);
            this.transitions = readTransitions(stream, header.sizeOfTransitionTargetTable);
        }

        @Override
        List<Analysis> analyze(String input) {
            InputCursor cursor = new InputCursor(input);
            List<Analysis> displayVector = new ArrayList<>();
            outputString = IndexList.singleton(NO_SYMBOL_NUMBER);
            inputString = new IndexList();
            while (cursor.hasMore()) {
                int previous = cursor.position();
                int symbol = letterTrie.findKey(cursor);
                inputString.append(symbol);
                if (symbol == NO_SYMBOL_NUMBER) {
                    break;
                }
                if (cursor.position() == previous) {
                    break;
                }
            }
            if (inputString.isEmpty() || inputString.lastValue() == NO_SYMBOL_NUMBER) {
                return Collections.emptyList();
            }
            inputString.append(NO_SYMBOL_NUMBER);
            getAnalyses(0, displayVector);
            return displayVector;
        }

        private void getAnalyses(long index, List<Analysis> displayVector) {
            if (index >= TRANSITION_TARGET_TABLE_START) {
                int baseIndex = (int) (index - TRANSITION_TARGET_TABLE_START);
                tryEpsilonTransitions(baseIndex + 1, displayVector);
                if (inputString.get() == NO_SYMBOL_NUMBER) {
                    if (isFinalTransition(baseIndex)) {
                        noteAnalysis(displayVector, 0.0);
                    }
                    outputString.put(NO_SYMBOL_NUMBER);
                    return;
                }
                inputString.increment();
                findTransitions(baseIndex + 1, displayVector);
            } else {
                int tableIndex = (int) index;
                tryEpsilonIndices(tableIndex + 1, displayVector);
                if (inputString.get() == NO_SYMBOL_NUMBER) {
                    if (indices[tableIndex].isFinal()) {
                        noteAnalysis(displayVector, 0.0);
                    }
                    outputString.put(NO_SYMBOL_NUMBER);
                    return;
                }
                inputString.increment();
                findIndex(tableIndex + 1, displayVector);
            }
            inputString.decrement();
            outputString.put(NO_SYMBOL_NUMBER);
        }

        private void noteAnalysis(List<Analysis> displayVector, double weight) {
            StringBuilder output = new StringBuilder();
            for (int value : outputString.values()) {
                if (value == NO_SYMBOL_NUMBER) {
                    break;
                }
                output.append(alphabet.keyTable.get(value));
            }
            displayVector.add(new Analysis(output.toString(), weight));
        }

        private void tryEpsilonIndices(int index, List<Analysis> displayVector) {
            if (index < indices.length && indices[index].inputSymbol == 0) {
                long target = indices[index].target - TRANSITION_TARGET_TABLE_START;
                if (target >= 0 && target < transitions.length) {
                    tryEpsilonTransitions((int) target + 1, displayVector);
                }
            }
        }

        private void tryEpsilonTransitions(int index, List<Analysis> displayVector) {
            while (index < transitions.length) {
                Transition transition = transitions[index];
                if (transition.inputSymbol == 0) {
                    traverse(index, displayVector);
                    index += 1;
                    continue;
                }
                FlagDiacriticOperation operation = alphabet.flagDiacriticOperations.get(transition.inputSymbol);
                if (operation != null) {
                    if (!stateStack.push(operation)) {
                        index += 1;
                        continue;
                    }
                    traverse(index, displayVector);
                    index += 1;
                    stateStack.pop();
                    continue;
                }
                return;
            }
        }

        private void findIndex(int index, List<Analysis> displayVector) {
            int adjustment = inputString.get(-1);
            int slot = index + adjustment;
            if (slot >= 0 && slot < indices.length) {
                TransitionIndex transitionIndex = indices[slot];
                if (transitionIndex.inputSymbol == adjustment) {
                    long target = transitionIndex.target - TRANSITION_TARGET_TABLE_START;
                    if (target >= 0 && target < transitions.length) {
                        findTransitions((int) target + 1, displayVector);
                    }
                }
            }
        }

        private void findTransitions(int index, List<Analysis> displayVector) {
            while (index < transitions.length) {
                Transition transition = transitions[index];
                if (transition.inputSymbol == NO_SYMBOL_NUMBER) {
                    return;
                }
                if (transition.inputSymbol == inputString.get(-1)) {
                    traverse(index, displayVector);
                } else {
                    return;
                }
                index += 1;
            }
        }

        private void traverse(int index, List<Analysis> displayVector) {
            Transition transition = transitions[index];
            outputString.put(transition.outputSymbol);
            outputString.increment();
            getAnalyses(transition.target, displayVector);
            outputString.decrement();
        }

        private boolean isFinalTransition(int index) {
            if (index < 0 || index >= transitions.length) {
                return false;
            }
            Transition transition = transitions[index];
            return transition.isFinal();
        }

        private static TransitionIndex[] readIndices(InputStream stream, int count) throws IOException {
            byte[] bytes = readFully(stream, count * 6);
            TransitionIndex[] result = new TransitionIndex[count];
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                int input = Short.toUnsignedInt(buffer.getShort());
                long target = Integer.toUnsignedLong(buffer.getInt());
                result[i] = new TransitionIndex(input, target);
            }
            return result;
        }

        private static Transition[] readTransitions(InputStream stream, int count) throws IOException {
            byte[] bytes = readFully(stream, count * 8);
            Transition[] result = new Transition[count];
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                int input = Short.toUnsignedInt(buffer.getShort());
                int output = Short.toUnsignedInt(buffer.getShort());
                long target = Integer.toUnsignedLong(buffer.getInt());
                result[i] = new Transition(input, output, target);
            }
            return result;
        }
    }

    private static final class WeightedTransducer extends AbstractTransducer {

        private final WeightedTransitionIndex[] indices;
        private final WeightedTransition[] transitions;
        private double currentWeight = 0.0;

        private WeightedTransducer(InputStream stream, Header header, Alphabet alphabet) throws IOException {
            super(alphabet, buildTrie(alphabet));
            this.indices = readIndices(stream, header.sizeOfTransitionIndexTable);
            this.transitions = readTransitions(stream, header.sizeOfTransitionTargetTable);
        }

        @Override
        List<Analysis> analyze(String input) {
            InputCursor cursor = new InputCursor(input);
            List<Analysis> displayVector = new ArrayList<>();
            outputString = IndexList.singleton(NO_SYMBOL_NUMBER);
            inputString = new IndexList();
            currentWeight = 0.0;
            while (cursor.hasMore()) {
                int previous = cursor.position();
                int symbol = letterTrie.findKey(cursor);
                inputString.append(symbol);
                if (symbol == NO_SYMBOL_NUMBER) {
                    break;
                }
                if (cursor.position() == previous) {
                    break;
                }
            }
            if (inputString.isEmpty() || inputString.lastValue() == NO_SYMBOL_NUMBER) {
                return Collections.emptyList();
            }
            inputString.append(NO_SYMBOL_NUMBER);
            getAnalyses(0, displayVector);
            return displayVector;
        }

        private void getAnalyses(long index, List<Analysis> displayVector) {
            if (index >= TRANSITION_TARGET_TABLE_START) {
                int baseIndex = (int) (index - TRANSITION_TARGET_TABLE_START);
                tryEpsilonTransitions(baseIndex + 1, displayVector);
                if (inputString.get() == NO_SYMBOL_NUMBER) {
                    if (isFinalTransition(baseIndex)) {
                        currentWeight += transitions[baseIndex].weight;
                        noteAnalysis(displayVector, currentWeight);
                        currentWeight -= transitions[baseIndex].weight;
                    }
                    outputString.put(NO_SYMBOL_NUMBER);
                    return;
                }
                inputString.increment();
                findTransitions(baseIndex + 1, displayVector);
            } else {
                int tableIndex = (int) index;
                tryEpsilonIndices(tableIndex + 1, displayVector);
                if (inputString.get() == NO_SYMBOL_NUMBER) {
                    if (indices[tableIndex].isFinal()) {
                        currentWeight += indices[tableIndex].getFinalWeight();
                        noteAnalysis(displayVector, currentWeight);
                        currentWeight -= indices[tableIndex].getFinalWeight();
                    }
                    outputString.put(NO_SYMBOL_NUMBER);
                    return;
                }
                inputString.increment();
                findIndex(tableIndex + 1, displayVector);
            }
            inputString.decrement();
            outputString.put(NO_SYMBOL_NUMBER);
        }

        private void noteAnalysis(List<Analysis> displayVector, double weight) {
            StringBuilder output = new StringBuilder();
            for (int value : outputString.values()) {
                if (value == NO_SYMBOL_NUMBER) {
                    break;
                }
                output.append(alphabet.keyTable.get(value));
            }
            displayVector.add(new Analysis(output.toString(), weight));
        }

        private void tryEpsilonIndices(int index, List<Analysis> displayVector) {
            if (index < indices.length && indices[index].inputSymbol == 0) {
                long target = indices[index].target - TRANSITION_TARGET_TABLE_START;
                if (target >= 0 && target < transitions.length) {
                    tryEpsilonTransitions((int) target + 1, displayVector);
                }
            }
        }

        private void tryEpsilonTransitions(int index, List<Analysis> displayVector) {
            while (index < transitions.length) {
                WeightedTransition transition = transitions[index];
                if (transition.inputSymbol == 0) {
                    traverse(index, displayVector);
                    index += 1;
                    continue;
                }
                FlagDiacriticOperation operation = alphabet.flagDiacriticOperations.get(transition.inputSymbol);
                if (operation != null) {
                    if (!stateStack.push(operation)) {
                        index += 1;
                        continue;
                    }
                    traverse(index, displayVector);
                    index += 1;
                    stateStack.pop();
                    continue;
                }
                return;
            }
        }

        private void findIndex(int index, List<Analysis> displayVector) {
            int adjustment = inputString.get(-1);
            int slot = index + adjustment;
            if (slot >= 0 && slot < indices.length) {
                WeightedTransitionIndex transitionIndex = indices[slot];
                if (transitionIndex.inputSymbol == adjustment) {
                    long target = transitionIndex.target - TRANSITION_TARGET_TABLE_START;
                    if (target >= 0 && target < transitions.length) {
                        findTransitions((int) target + 1, displayVector);
                    }
                }
            }
        }

        private void findTransitions(int index, List<Analysis> displayVector) {
            while (index < transitions.length) {
                WeightedTransition transition = transitions[index];
                if (transition.inputSymbol == NO_SYMBOL_NUMBER) {
                    return;
                }
                if (transition.inputSymbol == inputString.get(-1)) {
                    traverse(index, displayVector);
                } else {
                    return;
                }
                index += 1;
            }
        }

        private void traverse(int index, List<Analysis> displayVector) {
            WeightedTransition transition = transitions[index];
            outputString.put(transition.outputSymbol);
            outputString.increment();
            currentWeight += transition.weight;
            getAnalyses(transition.target, displayVector);
            outputString.decrement();
            currentWeight -= transition.weight;
        }

        private boolean isFinalTransition(int index) {
            if (index < 0 || index >= transitions.length) {
                return false;
            }
            return transitions[index].isFinal();
        }

        private static WeightedTransitionIndex[] readIndices(InputStream stream, int count) throws IOException {
            byte[] bytes = readFully(stream, count * 6);
            WeightedTransitionIndex[] result = new WeightedTransitionIndex[count];
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                int input = Short.toUnsignedInt(buffer.getShort());
                long target = Integer.toUnsignedLong(buffer.getInt());
                result[i] = new WeightedTransitionIndex(input, target);
            }
            return result;
        }

        private static WeightedTransition[] readTransitions(InputStream stream, int count) throws IOException {
            byte[] bytes = readFully(stream, count * 12);
            WeightedTransition[] result = new WeightedTransition[count];
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; i++) {
                int input = Short.toUnsignedInt(buffer.getShort());
                int output = Short.toUnsignedInt(buffer.getShort());
                long target = Integer.toUnsignedLong(buffer.getInt());
                double weight = buffer.getFloat();
                result[i] = new WeightedTransition(input, output, target, weight);
            }
            return result;
        }
    }

    private static class TransitionIndex {
        final int inputSymbol;
        final long target;

        private TransitionIndex(int inputSymbol, long target) {
            this.inputSymbol = inputSymbol;
            this.target = target;
        }

        boolean isFinal() {
            return inputSymbol == NO_SYMBOL_NUMBER && target != NO_TABLE_INDEX;
        }
    }

    private static class Transition {
        final int inputSymbol;
        final int outputSymbol;
        final long target;

        private Transition(int inputSymbol, int outputSymbol, long target) {
            this.inputSymbol = inputSymbol;
            this.outputSymbol = outputSymbol;
            this.target = target;
        }

        boolean isFinal() {
            return inputSymbol == NO_SYMBOL_NUMBER
                    && outputSymbol == NO_SYMBOL_NUMBER
                    && target == 1;
        }
    }

    private static final class WeightedTransitionIndex extends TransitionIndex {
        private WeightedTransitionIndex(int inputSymbol, long target) {
            super(inputSymbol, target);
        }

        private double getFinalWeight() {
            return (double) target;
        }
    }

    private static final class WeightedTransition extends Transition {
        private final double weight;

        private WeightedTransition(int inputSymbol, int outputSymbol, long target, double weight) {
            super(inputSymbol, outputSymbol, target);
            this.weight = weight;
        }
    }

    private static final class FlagDiacriticOperation {
        private final String operation;
        private final String feature;
        private final String value;

        private FlagDiacriticOperation(String operation, String feature, String value) {
            this.operation = operation;
            this.feature = feature;
            this.value = value;
        }
    }

    private static final class FlagDiacriticStateStack {
        private final ArrayDeque<Map<String, FlagState>> stack = new ArrayDeque<>();

        private FlagDiacriticStateStack() {
            stack.push(new HashMap<>());
        }

        private void pop() {
            if (stack.size() > 1) {
                stack.pop();
            }
        }

        private boolean push(FlagDiacriticOperation operation) {
            Map<String, FlagState> current = stack.peek();
            switch (operation.operation) {
                case "P":
                    stack.push(new HashMap<>(current));
                    stack.peek().put(operation.feature, new FlagState(operation.value, true));
                    return true;
                case "N":
                    stack.push(new HashMap<>(current));
                    stack.peek().put(operation.feature, new FlagState(operation.value, false));
                    return true;
                case "R":
                    FlagState required = current.get(operation.feature);
                    if (operation.value.isEmpty()) {
                        if (required == null) {
                            return false;
                        }
                        stack.push(new HashMap<>(current));
                        return true;
                    }
                    if (required != null && required.isPositive && Objects.equals(required.value, operation.value)) {
                        stack.push(new HashMap<>(current));
                        return true;
                    }
                    return false;
                case "D":
                    FlagState disallowed = current.get(operation.feature);
                    if (operation.value.isEmpty()) {
                        if (disallowed == null) {
                            stack.push(new HashMap<>(current));
                            return true;
                        }
                        return false;
                    }
                    if (disallowed != null && disallowed.isPositive && Objects.equals(disallowed.value, operation.value)) {
                        return false;
                    }
                    stack.push(new HashMap<>(current));
                    return true;
                case "C":
                    stack.push(new HashMap<>(current));
                    stack.peek().remove(operation.feature);
                    return true;
                case "U":
                    FlagState state = current.get(operation.feature);
                    if (state == null
                            || (state.isPositive && Objects.equals(state.value, operation.value))
                            || (!state.isPositive && !Objects.equals(state.value, operation.value))) {
                        stack.push(new HashMap<>(current));
                        stack.peek().put(operation.feature, new FlagState(operation.value, true));
                        return true;
                    }
                    return false;
                default:
                    return false;
            }
        }
    }

    private static final class FlagState {
        private final String value;
        private final boolean isPositive;

        private FlagState(String value, boolean isPositive) {
            this.value = value;
            this.isPositive = isPositive;
        }
    }

    private static final class LetterTrie {
        private final Node root = new Node();

        private void addString(String value, int symbolNumber) {
            List<String> segments = splitToSegments(value);
            root.add(segments, 0, symbolNumber);
        }

        private int findKey(InputCursor cursor) {
            return root.find(cursor);
        }

        private static List<String> splitToSegments(String value) {
            List<String> segments = new ArrayList<>();
            if (value.isEmpty()) {
                segments.add("");
                return segments;
            }
            value.codePoints().forEach(cp -> segments.add(new String(Character.toChars(cp))));
            return segments;
        }

        private static final class Node {
            private final Map<String, Integer> symbols = new HashMap<>();
            private final Map<String, Node> children = new HashMap<>();

            private void add(List<String> segments, int index, int symbolNumber) {
                if (segments.isEmpty()) {
                    symbols.put("", symbolNumber);
                    return;
                }
                if (index >= segments.size() - 1) {
                    symbols.put(segments.get(index), symbolNumber);
                    return;
                }
                Node child = children.computeIfAbsent(segments.get(index), key -> new Node());
                child.add(segments, index + 1, symbolNumber);
            }

            private int find(InputCursor cursor) {
                if (!cursor.hasMore()) {
                    return NO_SYMBOL_NUMBER;
                }
                String current = cursor.getAndAdvance();
                Node child = children.get(current);
                Integer direct = symbols.get(current);
                if (child == null) {
                    if (direct == null) {
                        cursor.retreat();
                        return NO_SYMBOL_NUMBER;
                    }
                    return direct;
                }
                int temp = child.find(cursor);
                if (temp == NO_SYMBOL_NUMBER) {
                    if (direct == null) {
                        cursor.retreat();
                        return NO_SYMBOL_NUMBER;
                    }
                    return direct;
                }
                return temp;
            }
        }
    }

    private static final class InputCursor {
        private final List<String> symbols;
        private int pos = 0;

        private InputCursor(String value) {
            if (value == null) {
                this.symbols = Collections.emptyList();
            } else {
                List<String> list = new ArrayList<>();
                value.codePoints().forEach(cp -> list.add(new String(Character.toChars(cp))));
                this.symbols = list;
            }
        }

        private boolean hasMore() {
            return pos < symbols.size();
        }

        private int position() {
            return pos;
        }

        private String getAndAdvance() {
            String value = symbols.get(pos);
            pos += 1;
            return value;
        }

        private void retreat() {
            if (pos > 0) {
                pos -= 1;
            }
        }
    }

    private static final class IndexList {
        private final List<Integer> values = new ArrayList<>();
        private int pos = 0;

        private static IndexList singleton(int value) {
            IndexList list = new IndexList();
            list.values.add(value);
            return list;
        }

        private void append(int value) {
            values.add(value);
        }

        private boolean isEmpty() {
            return values.isEmpty();
        }

        private int lastValue() {
            if (values.isEmpty()) {
                return NO_SYMBOL_NUMBER;
            }
            return values.get(values.size() - 1);
        }

        private int get() {
            return get(0);
        }

        private int get(int adjustment) {
            int index = pos + adjustment;
            if (index < 0 || index >= values.size()) {
                return NO_SYMBOL_NUMBER;
            }
            return values.get(index);
        }

        private void put(int value) {
            put(value, 0);
        }

        private void put(int value, int adjustment) {
            int index = pos + adjustment;
            while (values.size() <= index) {
                values.add(NO_SYMBOL_NUMBER);
            }
            values.set(index, value);
        }

        private void increment() {
            pos += 1;
        }

        private void decrement() {
            if (pos > 0) {
                pos -= 1;
            }
        }

        private List<Integer> values() {
            return values;
        }
    }

    private static LetterTrie buildTrie(Alphabet alphabet) {
        LetterTrie trie = new LetterTrie();
        for (int i = 0; i < alphabet.keyTable.size(); i++) {
            trie.addString(alphabet.keyTable.get(i), i);
        }
        return trie;
    }

    private static byte[] readFully(InputStream stream, int length) throws IOException {
        byte[] buffer = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = stream.read(buffer, offset, length - offset);
            if (read == -1) {
                throw new IOException("Unexpected EOF");
            }
            offset += read;
        }
        return buffer;
    }
}
