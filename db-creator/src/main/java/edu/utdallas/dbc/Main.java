package edu.utdallas.dbc;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.commons.io.FileUtils;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

public class Main {
    private static final String[] SUBJECTS;

    private static final File DB_BASE_DIR;

    private static final File DATA_BASE_DIR;

    private static final File SUBJECTS_BASE_DIR;

    private static final Map<String, Set<Pair<String, String>>> ROUTES_TABLE;

    private static final Map<String, Set<Triple<String, String, Pair<String, String>>>> DATA_TABLE;

    static {
        SUBJECTS = new String[] {"CommonsLang", "Gson", "JFreeChart", "Joda-Time"};
        DB_BASE_DIR = new File("db");
        DATA_BASE_DIR = FileUtils.getFile(DB_BASE_DIR, "manual-dataset", "data");
        SUBJECTS_BASE_DIR = new File(DB_BASE_DIR, "subjects");
        ROUTES_TABLE = new HashMap<>();
        DATA_TABLE = new HashMap<>();
    }

    public static void main(final String[] args) throws Exception {
        final File outDir = new File("out");
        if (outDir.isDirectory()) {
            FileUtils.forceDelete(outDir);
        }
        if (!outDir.mkdir()) {
            throw new IllegalStateException();
        }
        for (final String subject : SUBJECTS) {
            try (final PrintWriter pw = new PrintWriter(new File(outDir, subject + ".csv"))) {
                processSubject(subject, pw);
            }
        }
    }

    private static void processSubject(final String subject,
                                       final Appendable out) throws Exception {
        final CSVPrinter csvPrinter = new CSVPrinter(out, CSVFormat.DEFAULT);
        csvPrinter.printRecord("Test Id",
                "Method Id",
                "Method Role",
                "Method Modifiers",
                "Methods Header",
                "Fully Qualified Name",
                "Start Line",
                "End Line");
        loadDataTable(subject);
        loadRoutesTable(subject);
        ROUTES_TABLE.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(ent -> {
                    final String testId = ent.getKey();
                    for (final Pair<String, String> pair : ent.getValue()) {
                        final String methodId = pair.getLeft();
                        final String methodRole = pair.getRight();
                        final Set<Triple<String, String, Pair<String, String>>> methodsInfo = DATA_TABLE.get(methodId);
                        if (methodsInfo == null) {
                            throw new IllegalStateException();
                        }
                        for (final Triple<String, String, Pair<String, String>> methodInfo : methodsInfo) {
                            final String methodFullyQualifiedName = methodInfo.getMiddle();
                            final String modifiers = readMethodModifiers(subject, methodFullyQualifiedName);
                            final String startLine = methodInfo.getRight().getLeft();
                            final String endLine = methodInfo.getRight().getRight();
                            try {
                                if (modifiers == null) {
                                    csvPrinter.printRecord(testId, methodId, methodRole, "N/A", methodInfo.getLeft(), methodFullyQualifiedName, startLine, endLine);
                                } else {
                                    csvPrinter.printRecord(testId, methodId, methodRole, modifiers, methodInfo.getLeft(), methodFullyQualifiedName, startLine, endLine);
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e.getMessage(), e.getCause());
                            }
                        }
                    }
                });
        csvPrinter.close();
    }

    private static void loadRoutesTable(final String subject) {
        ROUTES_TABLE.clear();
        try (final Reader reader = new FileReader(new File(DATA_BASE_DIR, subject + "_routes.csv"));
             final CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT.withDelimiter(';'))) {
            final Iterator<CSVRecord> recordIterator = parser.iterator();
            recordIterator.next(); // skip header
            while (recordIterator.hasNext()) {
                final CSVRecord record = recordIterator.next();
                final String testId = record.get(0);
                final Set<Pair<String, String>> set = ROUTES_TABLE.computeIfAbsent(testId, k -> new HashSet<>());
                set.add(new ImmutablePair<>(record.get(1), record.get(2)));
            }
        } catch (final Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    private static void loadDataTable(final String subject) {
        DATA_TABLE.clear();
        try (final Reader reader = new FileReader(new File(DATA_BASE_DIR, subject + "_data.csv"));
             final CSVParser parser = new CSVParser(reader, CSVFormat.DEFAULT)) {
            final Iterator<CSVRecord> recordIterator = parser.iterator();
            recordIterator.next(); // skip header
            while (recordIterator.hasNext()) {
                final CSVRecord record = recordIterator.next();
                final Pair<String, String> startAndEndLines = new ImmutablePair<>(record.get(3), record.get(4));
                final String methodId = record.get(0);
                final Set<Triple<String, String, Pair<String, String>>> set = DATA_TABLE.computeIfAbsent(methodId, k -> new HashSet<>());
                set.add(new ImmutableTriple<>(record.get(1), record.get(2), startAndEndLines));
            }
        } catch (final Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    private static String readMethodModifiers(final String subject,
                                              final String methodFullSignature) {
        final class MyVisitor extends ClassVisitor {
            private final String fullName;

            String modifiers;

            public MyVisitor(final String fullName) {
                super(Opcodes.ASM7);
                this.fullName = fullName;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if ((name + descriptor).equals(this.fullName)) {
                    this.modifiers = Modifier.toString(access).replace("transient", "");
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }
        }
        int index = methodFullSignature.lastIndexOf('.');
        String classPath = methodFullSignature.substring(0, index);
        classPath = String.format("%s%s%s%s%s",
                subject,
                File.separator,
                "classes",
                File.separator,
                classPath.replace('.', File.separatorChar) + ".class");
        try (final InputStream fis = new FileInputStream(new File(SUBJECTS_BASE_DIR, classPath))) {
            final ClassReader reader = new ClassReader(fis);
            final MyVisitor visitor = new MyVisitor(methodFullSignature.substring(1 + methodFullSignature.lastIndexOf('.')));
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            return visitor.modifiers;
        } catch (final Exception e) {
            return null;
        }
    }
}
