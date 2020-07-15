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
import java.util.Iterator;
import java.util.Map;

public class Main {
    private static final String[] SUBJECTS;

    private static final File DB_BASE_DIR;

    private static final File DATA_BASE_DIR;

    private static final File SUBJECTS_BASE_DIR;

    private static final Map<String, Pair<String, String>> ROUTES_TABLE;

    private static final Map<String, Triple<String, String, Pair<String, String>>> DATA_TABLE;

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
        for (final Map.Entry<String, Pair<String, String>> pair : ROUTES_TABLE.entrySet()) {
            final String testId = pair.getKey();
            final String methodId = pair.getValue().getLeft();
            final String methodRole = pair.getValue().getRight();
            final Triple<String, String, Pair<String, String>> methodInfo = DATA_TABLE.get(methodId);
            if (methodInfo == null) {
                throw new IllegalStateException();
            }
            final String methodFullyQualifiedName = methodInfo.getMiddle();
            final String modifiers = readMethodModifiers(subject, methodFullyQualifiedName);
            final String startLine = methodInfo.getRight().getLeft();
            final String endLine = methodInfo.getRight().getRight();
            if (modifiers == null) {
                csvPrinter.printRecord(testId, methodId, methodRole, "N/A", methodInfo.getLeft(), methodFullyQualifiedName, startLine, endLine);
            } else {
                csvPrinter.printRecord(testId, methodId, methodRole, modifiers, methodInfo.getLeft(), methodFullyQualifiedName, startLine, endLine);
            }
        }
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
                ROUTES_TABLE.put(record.get(0), new ImmutablePair<>(record.get(1), record.get(2)));
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
                DATA_TABLE.put(record.get(0), new ImmutableTriple<>(record.get(1), record.get(2), startAndEndLines));
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
                    this.modifiers = Modifier.toString(access);
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
