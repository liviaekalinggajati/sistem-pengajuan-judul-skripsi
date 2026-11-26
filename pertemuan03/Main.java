package pertemuan03;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.awt.geom.Path2D;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.table.DefaultTableModel;

public class Main {
    private static final Color NAVY = new Color(24, 45, 79);
    private static final Color DEEP_NAVY = new Color(11, 31, 56);
    private static final Color TEAL = new Color(19, 132, 143);
    private static final Color PAGE_BG = new Color(239, 246, 251);
    private static final Color APPROVED = new Color(27, 149, 103);
    private static final Color WAITING = new Color(224, 139, 34);
    private static final Color REJECTED = new Color(202, 71, 79);
    private static final Color MUTED = new Color(92, 111, 132);
    private static final Path DATA_FILE = Path.of("data-mahasiswa.dat");
    private static final int DATA_FILE_MAGIC = 0x4D485331;

    public static void main(String[] args) {
        if (args.length > 0) {
            if (args.length == 2 && "--import-csv".equals(args[0])) {
                try {
                    ImportSummary summary = importCsv(Path.of(args[1]));
                    System.out.printf(
                            "Import selesai: %d profil baru (%d duplikat dilewati), "
                                    + "%d pengajuan baru (%d duplikat dilewati).%n",
                            summary.studentsAdded, summary.studentDuplicatesSkipped,
                            summary.applicationsAdded, summary.applicationDuplicatesSkipped);
                } catch (IOException | IllegalArgumentException ex) {
                    System.err.println("Import gagal: " + ex.getMessage());
                    System.exit(1);
                }
                return;
            }
            System.err.println("Penggunaan: pertemuan03.Main --import-csv <file.csv>");
            System.exit(2);
            return;
        }
        try {
            migrateLegacyFiles();
            int accountsCreated = provisionDummyStudentAccounts();
            System.out.printf("Akun login mahasiswa dummy siap; akun baru dibuat: %d.%n", accountsCreated);
        } catch (IOException | java.sql.SQLException | IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(
                    null,
                    "Akun mahasiswa dummy tidak dapat disiapkan:\n" + ex.getMessage(),
                    "Gagal menyiapkan akun",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }
        SwingUtilities.invokeLater(() -> new LoginUi().show());
    }

    static int provisionDummyStudentAccounts() throws IOException, java.sql.SQLException {
        List<Database.DummyStudentProfile> dummyProfiles = new ArrayList<>();
        Set<String> dummyNims = new HashSet<>();
        for (Student student : loadStudents()) {
            if (student.nim.matches("DEMO-SI-\\d{3}")) {
                dummyProfiles.add(new Database.DummyStudentProfile(
                        student.nim, student.nama, student.prodi, student.semester));
                dummyNims.add(student.nim);
            }
        }
        if (dummyProfiles.size() != 20 || dummyNims.size() != 20) {
            throw new IOException(
                    "Diharapkan 20 profil dummy DEMO-SI-001 sampai DEMO-SI-020; ditemukan "
                            + dummyNims.size() + ".");
        }
        for (int i = 1; i <= 20; i++) {
            String expectedNim = String.format(Locale.ROOT, "DEMO-SI-%03d", i);
            if (!dummyNims.contains(expectedNim)) {
                throw new IOException("Profil dummy " + expectedNim + " tidak ditemukan.");
            }
        }
        return Database.ensureDummyStudentAccounts(dummyProfiles);
    }

    private static void migrateLegacyFiles() throws IOException, java.sql.SQLException {
        if (Database.hasLegacyDataBeenImported()) {
            return;
        }
        List<Database.StudentRecord> students = loadStudentsFromFile().stream()
                .map(Main::toDatabaseStudent)
                .toList();
        List<Database.ThesisApplicationRecord> applications = ThesisUi.loadApplicationsFromFile().stream()
                .map(Main::toDatabaseApplication)
                .toList();
        Database.importLegacyData(students, applications);
    }

    private static ImportSummary importCsv(Path csvFile) throws IOException {
        List<CsvThesisRecord> records = readCsvRecords(csvFile);
        List<Student> students = loadStudents();
        List<ThesisApplication> applications = ThesisUi.loadApplications();
        Set<String> studentNims = new HashSet<>();
        Set<String> applicationKeys = new HashSet<>();
        for (Student student : students) {
            studentNims.add(normalizeImportKey(student.nim));
        }
        for (ThesisApplication application : applications) {
            applicationKeys.add(applicationImportKey(application.nim, application.judul));
        }

        int studentsAdded = 0;
        int applicationsAdded = 0;
        int studentDuplicatesSkipped = 0;
        int applicationDuplicatesSkipped = 0;
        for (CsvThesisRecord record : records) {
            String studentKey = normalizeImportKey(record.nim);
            String applicationKey = applicationImportKey(record.nim, record.title);
            boolean studentExists = !studentNims.add(studentKey);
            boolean applicationExists = !applicationKeys.add(applicationKey);
            if (studentExists) {
                studentDuplicatesSkipped++;
            }
            if (applicationExists) {
                applicationDuplicatesSkipped++;
            }
            if (!studentExists) {
                students.add(new Student(record.name, record.nim, "Sistem Informasi", 8, Double.NaN));
                studentsAdded++;
            }
            if (!applicationExists) {
                applications.add(new ThesisApplication(
                        UUID.randomUUID().toString(),
                        record.name,
                        record.nim,
                        "Sistem Informasi",
                        importEmail(record.nim),
                        "Semester 8",
                        record.title,
                        "Penelitian ini membahas perancangan dan pengembangan: " + record.title + ".",
                        importResearchField(record.title),
                        record.status,
                        "",
                        "2026-11-16T00:00:00"));
                applicationsAdded++;
            }
        }

        if (students.size() > 100_000 || applications.size() > 100_000) {
            throw new IOException("Jumlah data setelah import melebihi batas penyimpanan.");
        }
        if (studentsAdded > 0) {
            saveStudents(students);
        }
        if (applicationsAdded > 0) {
            ThesisUi.saveApplications(applications);
        }
        return new ImportSummary(
                studentsAdded, studentDuplicatesSkipped,
                applicationsAdded, applicationDuplicatesSkipped);
    }

    private static List<CsvThesisRecord> readCsvRecords(Path csvFile) throws IOException {
        String content = Files.readString(csvFile, StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
            content = content.substring(1);
        }
        List<List<String>> rows = parseCsv(content);
        if (rows.isEmpty()) {
            throw new IOException("CSV kosong.");
        }
        List<String> expectedHeader = List.of("nim", "nama", "judul", "status");
        List<String> header = new ArrayList<>(rows.get(0).size());
        for (String value : rows.get(0)) {
            header.add(value.trim().toLowerCase(Locale.ROOT));
        }
        if (!expectedHeader.equals(header)) {
            throw new IOException("Header CSV harus berurutan: NIM,Nama,Judul,Status.");
        }

        List<CsvThesisRecord> records = new ArrayList<>();
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            int rowNumber = i + 1;
            if (row.size() != expectedHeader.size()) {
                throw new IOException("Baris " + rowNumber + " harus memiliki 4 kolom.");
            }
            String nim = row.get(0).trim();
            String name = row.get(1).trim();
            String title = row.get(2).trim();
            String status = normalizeImportStatus(row.get(3), rowNumber);
            if (nim.isEmpty() || name.isEmpty() || title.isEmpty()) {
                throw new IOException("NIM, nama, dan judul wajib diisi pada baris " + rowNumber + ".");
            }
            records.add(new CsvThesisRecord(nim, name, title, status));
        }
        if (records.isEmpty()) {
            throw new IOException("CSV tidak berisi baris data.");
        }
        return records;
    }

    private static List<List<String>> parseCsv(String content) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean quoteClosed = false;
        for (int i = 0; i < content.length(); i++) {
            char current = content.charAt(i);
            if (quoted) {
                if (current == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        quoteClosed = true;
                    }
                } else {
                    field.append(current);
                }
            } else if (current == ',') {
                row.add(field.toString());
                field.setLength(0);
                quoteClosed = false;
            } else if (current == '\r' || current == '\n') {
                row.add(field.toString());
                field.setLength(0);
                if (!(row.size() == 1 && row.get(0).isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
                quoteClosed = false;
                if (current == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
            } else if (current == '"') {
                if (field.length() == 0 && !quoteClosed) {
                    quoted = true;
                } else {
                    throw new IOException("CSV memiliki tanda kutip yang tidak valid.");
                }
            } else if (quoteClosed) {
                throw new IOException("CSV memiliki karakter setelah tanda kutip penutup.");
            } else {
                field.append(current);
            }
        }
        if (quoted) {
            throw new IOException("CSV memiliki tanda kutip yang tidak berpasangan.");
        }
        if (field.length() > 0 || !row.isEmpty() || quoteClosed) {
            row.add(field.toString());
            if (!(row.size() == 1 && row.get(0).isEmpty())) {
                rows.add(row);
            }
        }
        return rows;
    }

    private static String normalizeImportStatus(String status, int rowNumber) throws IOException {
        String normalized = status.trim();
        if ("Menunggu".equalsIgnoreCase(normalized)
                || "Menunggu Verifikasi".equalsIgnoreCase(normalized)) {
            return "Menunggu Verifikasi";
        }
        if ("Disetujui".equalsIgnoreCase(normalized)) {
            return "Disetujui";
        }
        if ("Ditolak".equalsIgnoreCase(normalized)) {
            return "Ditolak";
        }
        throw new IOException("Status tidak dikenal pada baris " + rowNumber + ".");
    }

    private static String normalizeImportKey(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String applicationImportKey(String nim, String title) {
        return normalizeImportKey(nim) + "\u0000" + normalizeImportKey(title);
    }

    private static String importEmail(String nim) {
        String localPart = nim.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._+-]", "-");
        return localPart + "@example.com";
    }

    private static String importResearchField(String title) {
        String normalized = title.toLowerCase(Locale.ROOT);
        if (normalized.matches(".*(kecerdasan buatan|machine learning|deep learning|data mining|analitik).*")) {
            return "Kecerdasan Buatan dan Data";
        }
        if (normalized.matches(".*(jaringan|keamanan|cyber|kriptografi).*")) {
            return "Jaringan dan Keamanan";
        }
        if (normalized.matches(".*(keuangan|penjualan|umkm|kantin|bisnis|beasiswa).*")) {
            return "Ekonomi dan Bisnis";
        }
        if (normalized.matches(".*(pendidikan|pembelajaran|sekolah|dosen|kuliah).*")) {
            return "Pendidikan";
        }
        if (normalized.matches(".*(hukum|perizinan).*")) {
            return "Hukum";
        }
        return "Sistem Informasi";
    }

    private static final class CsvThesisRecord {
        private final String nim;
        private final String name;
        private final String title;
        private final String status;

        private CsvThesisRecord(String nim, String name, String title, String status) {
            this.nim = nim;
            this.name = name;
            this.title = title;
            this.status = status;
        }
    }

    private static final class ImportSummary {
        private final int studentsAdded;
        private final int studentDuplicatesSkipped;
        private final int applicationsAdded;
        private final int applicationDuplicatesSkipped;

        private ImportSummary(
                int studentsAdded, int studentDuplicatesSkipped,
                int applicationsAdded, int applicationDuplicatesSkipped) {
            this.studentsAdded = studentsAdded;
            this.studentDuplicatesSkipped = studentDuplicatesSkipped;
            this.applicationsAdded = applicationsAdded;
            this.applicationDuplicatesSkipped = applicationDuplicatesSkipped;
        }
    }

    private static boolean authenticateAdmin(String username, char[] password)
            throws java.sql.SQLException {
        return Database.authenticateAdmin(username, password);
    }

    private static boolean authenticateAdminPassword(char[] password)
            throws java.sql.SQLException {
        return Database.authenticateAdmin("admin", password);
    }

    private static void validateLegacyStudentProfile(
            String nim, String name, String program, int semester) throws IOException {
        boolean legacyProfileFound = false;
        List<Student> students = loadStudents();
        for (Student student : students) {
            if (!student.nim.equalsIgnoreCase(nim)) {
                continue;
            }
            legacyProfileFound = true;
            if (sameStudentIdentity(student.nama, student.prodi, student.semester,
                    name, program, semester)) {
                return;
            }
        }

        for (ThesisApplication application : ThesisUi.loadApplications()) {
            if (!application.nim.equalsIgnoreCase(nim)) {
                continue;
            }
            legacyProfileFound = true;
            if (sameStudentIdentity(application.nama, application.prodi, application.semester,
                    name, program, semester)) {
                return;
            }
        }

        if (legacyProfileFound) {
            throw new IOException(
                    "NIM ini sudah terhubung dengan data lama. Nama, program studi, dan semester harus sesuai.");
        }
    }

    private static boolean sameStudentIdentity(
            String storedName, String storedProgram, int storedSemester,
            String enteredName, String enteredProgram, int enteredSemester) {
        return normalizeImportKey(storedName).equals(normalizeImportKey(enteredName))
                && normalizeImportKey(storedProgram).equals(normalizeImportKey(enteredProgram))
                && storedSemester == enteredSemester;
    }

    private static boolean sameStudentIdentity(
            String storedName, String storedProgram, String storedSemester,
            String enteredName, String enteredProgram, int enteredSemester) {
        String semesterDigits = storedSemester == null ? "" : storedSemester.replaceAll("\\D+", "");
        if (semesterDigits.isEmpty()) {
            return normalizeImportKey(storedName).equals(normalizeImportKey(enteredName))
                    && normalizeImportKey(storedProgram).equals(normalizeImportKey(enteredProgram));
        }
        try {
            return sameStudentIdentity(storedName, storedProgram, Integer.parseInt(semesterDigits),
                    enteredName, enteredProgram, enteredSemester);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static void createWindow() {
        JFrame frame = new JFrame("Input Data Mahasiswa");
        ImageIcon logo = loadLogo();
        BufferedImage backgroundImage = loadBackgroundImage();
        frame.setMinimumSize(new java.awt.Dimension(900, 700));

        GradientPanel header = new GradientPanel();
        header.setLayout(new BorderLayout(28, 0));
        header.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 36));
        header.setPreferredSize(new java.awt.Dimension(960, 150));
        if (logo != null) {
            ImageIcon smallLogo = new ImageIcon(
                    logo.getImage().getScaledInstance(96, 96, Image.SCALE_SMOOTH)
            );
            JLabel logoLabel = new JLabel(smallLogo);
            logoLabel.setOpaque(false);
            header.add(logoLabel, BorderLayout.WEST);
            frame.setIconImage(logo.getImage());
        }

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel eyebrow = new JLabel("UNIVERSITAS HASYIM ASY'ARI");
        eyebrow.setFont(new Font("SansSerif", Font.BOLD, 15));
        eyebrow.setForeground(new Color(220, 235, 242));
        JLabel title = new JLabel("Data Mahasiswa");
        title.setFont(new Font("SansSerif", Font.BOLD, 34));
        title.setForeground(Color.WHITE);
        JLabel subtitle = new JLabel("Lengkapi informasi mahasiswa di bawah ini");
        subtitle.setFont(new Font("SansSerif", Font.ITALIC, 16));
        subtitle.setForeground(new Color(223, 235, 242));
        heading.add(eyebrow);
        heading.add(Box.createVerticalStrut(5));
        heading.add(title);
        heading.add(Box.createVerticalStrut(4));
        heading.add(subtitle);
        header.add(heading, BorderLayout.CENTER);

        JTextField textNama = createInput("Masukkan nama lengkap");
        JTextField textNim = createInput("Masukkan NIM");
        JComboBox<String> textProdi = createChoice(
                "Pilih program studi",
                "Sistem Informasi",
                "Teknik Informatika",
                "Teknologi Informasi"
        );
        String[] semesterOptions = new String[9];
        semesterOptions[0] = "Pilih semester";
        for (int semester = 1; semester <= 8; semester++) {
            semesterOptions[semester] = String.valueOf(semester);
        }
        JComboBox<String> textSemester = createChoice(semesterOptions);
        JTextField textNilai = createInput("Masukkan nilai");

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setOpaque(false);
        form.add(createFormRow("Nama Lengkap", textNama, FormIcon.PERSON));
        form.add(Box.createVerticalStrut(5));
        form.add(createFormRow("NIM", textNim, FormIcon.ID));
        form.add(Box.createVerticalStrut(5));
        form.add(createFormRow("Program Studi", textProdi, FormIcon.GRADUATION));
        form.add(Box.createVerticalStrut(5));
        form.add(createFormRow("Semester", textSemester, FormIcon.CALENDAR));
        form.add(Box.createVerticalStrut(5));
        form.add(createFormRow("Nilai", textNilai, FormIcon.CHART));

        JPanel card = new GlassPanel();
        card.setLayout(new BorderLayout(0, 14));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 255, 255, 225), 2),
                BorderFactory.createEmptyBorder(14, 18, 14, 18)
        ));
        card.setMaximumSize(new java.awt.Dimension(780, Integer.MAX_VALUE));
        card.add(form, BorderLayout.CENTER);

        JButton buttonSimpan = new RoundedButton("Simpan Data", TEAL, Color.WHITE);
        JButton buttonReset = new ResetButton();
        JButton buttonLihatData = new RoundedButton("Lihat Data Tersimpan", NAVY, Color.WHITE);
        buttonSimpan.setFont(new Font("SansSerif", Font.BOLD, 17));
        JPanel buttons = new JPanel(new GridBagLayout());
        buttons.setOpaque(false);
        GridBagConstraints buttonConstraints = new GridBagConstraints();
        buttonConstraints.gridy = 0;
        buttonConstraints.fill = GridBagConstraints.HORIZONTAL;
        buttonConstraints.weightx = 3;
        buttonConstraints.insets = new Insets(0, 0, 0, 12);
        buttons.add(buttonSimpan, buttonConstraints);
        buttonConstraints.gridx = 1;
        buttonConstraints.weightx = 1;
        buttonConstraints.insets = new Insets(0, 0, 0, 0);
        buttons.add(buttonReset, buttonConstraints);
        buttonConstraints.gridx = 2;
        buttonConstraints.weightx = 2;
        buttonConstraints.insets = new Insets(0, 12, 0, 0);
        buttons.add(buttonLihatData, buttonConstraints);
        card.add(buttons, BorderLayout.SOUTH);
        card.setPreferredSize(new java.awt.Dimension(780, card.getPreferredSize().height));

        JPanel content = new BackgroundPanel(backgroundImage);
        content.setLayout(new BorderLayout(0, 16));
        content.add(header, BorderLayout.NORTH);
        JPanel cardWrapper = new JPanel(new GridBagLayout());
        cardWrapper.setOpaque(false);
        cardWrapper.setBorder(BorderFactory.createEmptyBorder(8, 40, 18, 40));
        GridBagConstraints cardConstraints = new GridBagConstraints();
        cardConstraints.fill = GridBagConstraints.HORIZONTAL;
        cardConstraints.weightx = 0;
        cardConstraints.anchor = GridBagConstraints.CENTER;
        cardWrapper.add(card, cardConstraints);
        content.add(cardWrapper, BorderLayout.CENTER);
        frame.setContentPane(content);

        buttonSimpan.addActionListener(e -> {
            String nama = textNama.getText().trim();
            String nim = textNim.getText().trim();
            String prodi = (String) textProdi.getSelectedItem();
            String semesterInput = (String) textSemester.getSelectedItem();
            String nilaiInput = textNilai.getText().trim();

            if (nama.isEmpty() || nim.isEmpty()
                    || textProdi.getSelectedIndex() == 0
                    || textSemester.getSelectedIndex() == 0
                    || nilaiInput.isEmpty()) {
                JOptionPane.showMessageDialog(
                        frame,
                        "Semua data harus diisi.",
                        "Data belum lengkap",
                        JOptionPane.WARNING_MESSAGE
                );
                return;
            }

            try {
                int semester = Integer.parseInt(semesterInput);
                double nilai = Double.parseDouble(nilaiInput);
                List<Student> students = loadStudents();
                Student student = new Student(nama, nim, prodi, semester, nilai);
                students.add(student);
                saveStudents(students);
                showSavedDataDialog(frame, student, true);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(
                        frame,
                        "Data tidak dapat disimpan: " + ex.getMessage(),
                        "Gagal menyimpan data",
                        JOptionPane.ERROR_MESSAGE
                );
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(
                        frame,
                        "Nilai harus berupa angka.",
                        "Input tidak valid",
                        JOptionPane.ERROR_MESSAGE
                );
            }
        });

        buttonReset.addActionListener(e -> {
            textNama.setText("");
            textNim.setText("");
            textProdi.setSelectedIndex(0);
            textSemester.setSelectedIndex(0);
            textNilai.setText("");
        });

        buttonLihatData.addActionListener(e -> showStoredDataDialog(frame));

        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.pack();
        frame.setSize(1140, 850);
        frame.setMinimumSize(new java.awt.Dimension(900, 700));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void showStoredDataDialog(JFrame owner) {
        List<Student> students;
        try {
            students = loadStudents();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(
                    owner,
                    "Data tersimpan tidak dapat dibaca: " + ex.getMessage(),
                    "Gagal membaca data",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        if (students.isEmpty()) {
            JOptionPane.showMessageDialog(
                    owner,
                    "Belum ada data mahasiswa yang tersimpan.",
                    "Data tersimpan",
                    JOptionPane.INFORMATION_MESSAGE
            );
            return;
        }

        JDialog dialog = new JDialog(owner, "Data Mahasiswa Tersimpan", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setUndecorated(true);
        dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel content = new SuccessBackgroundPanel();
        content.setLayout(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(216, 232, 247), 1),
                BorderFactory.createEmptyBorder(10, 22, 20, 22)
        ));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("Data Mahasiswa Tersimpan");
        title.setFont(new Font("SansSerif", Font.BOLD, 24));
        title.setForeground(NAVY);
        JLabel subtitle = new JLabel(storedDataSubtitle(students.size()));
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 14));
        subtitle.setForeground(MUTED);
        heading.add(title);
        heading.add(Box.createVerticalStrut(5));
        heading.add(subtitle);
        header.add(heading, BorderLayout.CENTER);

        JButton closeButton = new JButton("×");
        closeButton.setFont(new Font("SansSerif", Font.PLAIN, 25));
        closeButton.setForeground(MUTED);
        closeButton.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 4));
        closeButton.setContentAreaFilled(false);
        closeButton.setFocusPainted(false);
        closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeButton.addActionListener(e -> dialog.dispose());
        header.add(closeButton, BorderLayout.EAST);
        content.add(header, BorderLayout.NORTH);

        JPanel listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setOpaque(false);
        listPanel.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 8));
        populateStoredStudentList(owner, dialog, listPanel, subtitle, students);

        JScrollPane scrollPane = new JScrollPane(listPanel);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.getVerticalScrollBar().setUnitIncrement(18);
        content.add(scrollPane, BorderLayout.CENTER);

        dialog.setContentPane(content);
        dialog.setSize(680, 650);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static String storedDataSubtitle(int count) {
        return count + " data tersimpan  •  Klik kartu untuk melihat detail";
    }

    private static void populateStoredStudentList(
            JFrame owner, JDialog dialog, JPanel listPanel, JLabel subtitle, List<Student> students) {
        listPanel.removeAll();
        subtitle.setText(storedDataSubtitle(students.size()));

        if (students.isEmpty()) {
            JLabel emptyMessage = new JLabel("Belum ada data mahasiswa yang tersimpan.");
            emptyMessage.setFont(new Font("SansSerif", Font.PLAIN, 15));
            emptyMessage.setForeground(MUTED);
            emptyMessage.setBorder(BorderFactory.createEmptyBorder(20, 12, 20, 12));
            emptyMessage.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            listPanel.add(emptyMessage);
        } else {
            for (Student student : students) {
                JPanel studentCard = createStudentCard(owner, dialog, student, listPanel, subtitle);
                studentCard.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
                studentCard.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 78));
                listPanel.add(studentCard);
                listPanel.add(Box.createVerticalStrut(8));
            }
        }
        listPanel.revalidate();
        listPanel.repaint();
    }

    private static JPanel createStudentCard(
            JFrame owner, JDialog dialog, Student student, JPanel listPanel, JLabel subtitle) {
        ResultDetailsPanel card = new ResultDetailsPanel();
        card.setLayout(new BorderLayout(12, 0));
        card.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        card.setPreferredSize(new java.awt.Dimension(600, 72));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.setToolTipText("Klik untuk melihat detail lengkap");

        IconBadge badge = new IconBadge(FormIcon.PERSON);
        badge.setPreferredSize(new java.awt.Dimension(44, 44));
        badge.setMinimumSize(new java.awt.Dimension(44, 44));
        badge.setMaximumSize(new java.awt.Dimension(44, 44));
        card.add(badge, BorderLayout.WEST);

        JPanel labels = new JPanel();
        labels.setOpaque(false);
        labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
        JLabel name = new JLabel(student.nama);
        name.setFont(new Font("SansSerif", Font.BOLD, 15));
        name.setForeground(NAVY);
        JLabel summary = new JLabel(student.nim + "  •  " + student.prodi);
        summary.setFont(new Font("SansSerif", Font.PLAIN, 13));
        summary.setForeground(MUTED);
        labels.add(name);
        labels.add(Box.createVerticalStrut(4));
        labels.add(summary);
        card.add(labels, BorderLayout.CENTER);

        JPanel actions = new JPanel(new BorderLayout(8, 0));
        actions.setOpaque(false);
        JButton deleteButton = new JButton(new TrashIcon());
        deleteButton.setForeground(Color.WHITE);
        deleteButton.setBackground(new Color(190, 65, 72));
        deleteButton.setFocusPainted(false);
        deleteButton.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
        deleteButton.setPreferredSize(new java.awt.Dimension(40, 40));
        deleteButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        deleteButton.setToolTipText("Hapus data ini");
        deleteButton.getAccessibleContext().setAccessibleName("Hapus data");
        deleteButton.addActionListener(e ->
                requestStudentDeletion(owner, dialog, student, listPanel, subtitle));
        actions.add(deleteButton, BorderLayout.CENTER);

        JLabel arrow = new JLabel("›");
        arrow.setFont(new Font("SansSerif", Font.PLAIN, 28));
        arrow.setForeground(TEAL);
        actions.add(arrow, BorderLayout.EAST);
        card.add(actions, BorderLayout.EAST);

        MouseAdapter openDetails = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                showSavedDataDialog(owner, student, false);
            }

            @Override
            public void mouseEntered(MouseEvent event) {
                card.setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                java.awt.Point point = javax.swing.SwingUtilities.convertPoint(
                        event.getComponent(), event.getPoint(), card);
                if (!card.contains(point)) {
                    card.setHovered(false);
                }
            }
        };
        addClickListenerToTree(card, openDetails);
        return card;
    }

    private static void addClickListenerToTree(java.awt.Component component, MouseAdapter listener) {
        if (!(component instanceof JButton)) {
            component.addMouseListener(listener);
        }
        if (component instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) component).getComponents()) {
                addClickListenerToTree(child, listener);
            }
        }
    }

    private static void requestStudentDeletion(
            JFrame owner, JDialog dialog, Student student, JPanel listPanel, JLabel subtitle) {
        char[] enteredPassword = showAdminPasswordDialog(dialog);
        if (enteredPassword == null) {
            return;
        }

        boolean passwordCorrect;
        try {
            passwordCorrect = authenticateAdminPassword(enteredPassword);
        } catch (java.sql.SQLException ex) {
            JOptionPane.showMessageDialog(
                    dialog,
                    "Verifikasi admin tidak dapat dilakukan: " + ex.getMessage(),
                    "Kesalahan database",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        } finally {
            Arrays.fill(enteredPassword, '\0');
        }
        if (!passwordCorrect) {
            JOptionPane.showMessageDialog(
                    dialog,
                    "Password admin salah. Data tidak dihapus.",
                    "Verifikasi gagal",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        int confirmation = JOptionPane.showConfirmDialog(
                dialog,
                "Hapus data mahasiswa atas nama " + student.nama
                        + " (NIM " + student.nim + ")?",
                "Konfirmasi penghapusan",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (confirmation != JOptionPane.YES_OPTION) {
            return;
        }

        try {
            List<Student> students = loadStudents();
            int studentIndex = -1;
            for (int i = 0; i < students.size(); i++) {
                if (sameStudent(students.get(i), student)) {
                    studentIndex = i;
                    break;
                }
            }
            if (studentIndex < 0) {
                JOptionPane.showMessageDialog(
                        dialog,
                        "Data yang dipilih sudah tidak ditemukan. Daftar akan diperbarui.",
                        "Data tidak ditemukan",
                        JOptionPane.WARNING_MESSAGE
                );
                populateStoredStudentList(owner, dialog, listPanel, subtitle, loadStudents());
                return;
            }

            students.remove(studentIndex);
            saveStudents(students);
            populateStoredStudentList(owner, dialog, listPanel, subtitle, students);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(
                    dialog,
                    "Data tidak dapat dihapus: " + ex.getMessage(),
                    "Gagal menghapus data",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private static char[] showAdminPasswordDialog(JDialog owner) {
        JDialog passwordDialog = new JDialog(owner, "Verifikasi Admin", true);
        passwordDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        passwordDialog.setUndecorated(true);
        passwordDialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel content = new SuccessBackgroundPanel();
        content.setLayout(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(216, 232, 247), 1),
                BorderFactory.createEmptyBorder(12, 24, 20, 24)
        ));

        JButton closeButton = new JButton("×");
        closeButton.setFont(new Font("SansSerif", Font.PLAIN, 24));
        closeButton.setForeground(MUTED);
        closeButton.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 2));
        closeButton.setContentAreaFilled(false);
        closeButton.setFocusPainted(false);
        closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeButton.setToolTipText("Tutup");
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setOpaque(false);
        topBar.add(closeButton, BorderLayout.EAST);
        content.add(topBar, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setOpaque(false);
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        IconBadge securityIcon = new IconBadge(FormIcon.LOCK);
        securityIcon.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        JLabel title = new JLabel("Verifikasi Admin");
        title.setFont(new Font("SansSerif", Font.BOLD, 23));
        title.setForeground(NAVY);
        title.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        JLabel description = new JLabel("Masukkan password untuk menghapus data");
        description.setFont(new Font("SansSerif", Font.PLAIN, 14));
        description.setForeground(MUTED);
        description.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

        JLabel passwordLabel = new JLabel("Password admin");
        passwordLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        passwordLabel.setForeground(NAVY);
        passwordLabel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        JPasswordField passwordField = new JPasswordField();
        passwordField.setFont(new Font("SansSerif", Font.PLAIN, 15));
        passwordField.setForeground(NAVY);
        passwordField.setOpaque(false);
        passwordField.setCaretColor(TEAL);
        passwordField.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        passwordField.getAccessibleContext().setAccessibleName("Password admin");
        Runnable cancel = () -> {
            passwordField.setText("");
            passwordDialog.dispose();
        };
        closeButton.addActionListener(e -> cancel.run());
        JPanel field = new RoundedFieldPanel();
        field.setLayout(new BorderLayout());
        field.setPreferredSize(new java.awt.Dimension(360, 42));
        field.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 42));
        field.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        field.add(passwordField, BorderLayout.CENTER);

        body.add(securityIcon);
        body.add(Box.createVerticalStrut(8));
        body.add(title);
        body.add(Box.createVerticalStrut(4));
        body.add(description);
        body.add(Box.createVerticalStrut(22));
        body.add(passwordLabel);
        body.add(Box.createVerticalStrut(6));
        body.add(field);

        char[][] enteredPassword = new char[1][];
        JButton cancelButton = new RoundedButton("Batal", NAVY, Color.WHITE);
        cancelButton.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));
        cancelButton.addActionListener(e -> cancel.run());
        JButton verifyButton = new RoundedButton("Verifikasi", TEAL, Color.WHITE);
        verifyButton.setBorder(BorderFactory.createEmptyBorder(10, 22, 10, 22));
        verifyButton.addActionListener(e -> {
            enteredPassword[0] = passwordField.getPassword();
            passwordField.setText("");
            passwordDialog.dispose();
        });
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 10, 0));
        buttons.setOpaque(false);
        buttons.add(cancelButton);
        buttons.add(verifyButton);
        JPanel main = new JPanel(new BorderLayout(0, 20));
        main.setOpaque(false);
        main.add(body, BorderLayout.CENTER);
        main.add(buttons, BorderLayout.SOUTH);
        content.add(main, BorderLayout.CENTER);

        passwordDialog.setContentPane(content);
        passwordDialog.getRootPane().setDefaultButton(verifyButton);
        passwordDialog.getRootPane().registerKeyboardAction(
                e -> cancel.run(),
                javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW
        );
        passwordDialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent event) {
                passwordField.requestFocusInWindow();
            }
        });
        passwordDialog.setSize(480, 330);
        passwordDialog.setLocationRelativeTo(owner);
        passwordDialog.setVisible(true);
        return enteredPassword[0];
    }

    private static boolean sameStudent(Student first, Student second) {
        return first.nama.equals(second.nama)
                && first.nim.equals(second.nim)
                && first.prodi.equals(second.prodi)
                && first.semester == second.semester
                && Double.compare(first.nilai, second.nilai) == 0;
    }

    private static void showSavedDataDialog(JFrame owner, Student student, boolean justSaved) {
        String dialogTitle = justSaved
                ? "Data Mahasiswa Berhasil Disimpan" : "Detail Data Mahasiswa";
        JDialog dialog = new JDialog(owner, dialogTitle, true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.setUndecorated(true);
        dialog.getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel content = new SuccessBackgroundPanel();
        content.setLayout(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(229, 241, 231), 1),
                BorderFactory.createEmptyBorder(10, 28, 14, 28)
        ));

        JPanel closeRow = new JPanel(new BorderLayout());
        closeRow.setOpaque(false);
        JButton closeButton = new JButton("×");
        closeButton.setFont(new Font("SansSerif", Font.PLAIN, 24));
        closeButton.setForeground(new Color(74, 91, 95));
        closeButton.setBorderPainted(false);
        closeButton.setContentAreaFilled(false);
        closeButton.setFocusPainted(false);
        closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeButton.addActionListener(e -> dialog.dispose());
        closeRow.add(closeButton, BorderLayout.EAST);
        content.add(closeRow, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setOpaque(false);
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        SuccessCheckIcon checkIcon = new SuccessCheckIcon();
        checkIcon.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        JLabel title = new JLabel(dialogTitle);
        title.setFont(new Font("SansSerif", Font.BOLD, 25));
        title.setForeground(NAVY);
        title.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        JLabel message = new JLabel(justSaved
                ? "Data mahasiswa berhasil disimpan."
                : "Data ini tersimpan di perangkat ini.");
        message.setFont(new Font("SansSerif", Font.PLAIN, 16));
        message.setForeground(MUTED);
        message.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
        center.add(checkIcon);
        center.add(Box.createVerticalStrut(3));
        center.add(title);
        center.add(Box.createVerticalStrut(3));
        center.add(message);
        center.add(Box.createVerticalStrut(10));

        center.add(createStudentDetailsPanel(student));

        JButton okButton = new RoundedButton("OK   →", TEAL, Color.WHITE);
        okButton.setFont(new Font("SansSerif", Font.BOLD, 17));
        okButton.setBorder(BorderFactory.createEmptyBorder(10, 48, 10, 48));
        okButton.addActionListener(e -> dialog.dispose());
        JPanel buttonPanel = new JPanel();
        buttonPanel.setOpaque(false);
        buttonPanel.add(okButton);

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        body.add(center, BorderLayout.CENTER);
        body.add(buttonPanel, BorderLayout.SOUTH);
        content.add(body, BorderLayout.CENTER);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(okButton);
        dialog.setSize(740, 620);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
    }

    private static ResultDetailsPanel createStudentDetailsPanel(Student student) {
        ResultDetailsPanel details = new ResultDetailsPanel();
        details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
        details.setBorder(BorderFactory.createEmptyBorder(8, 18, 8, 18));
        details.add(createResultRow("Nama", student.nama, FormIcon.PERSON));
        details.add(createResultRow("NIM", student.nim, FormIcon.ID));
        details.add(createResultRow("Program Studi", student.prodi, FormIcon.GRADUATION));
        details.add(createResultRow("Semester", String.valueOf(student.semester), FormIcon.CALENDAR));
        details.add(createResultRow(
                "Nilai", Double.isNaN(student.nilai) ? "" : String.valueOf(student.nilai), FormIcon.STAR));
        return details;
    }

    private static List<Student> loadStudents() throws IOException {
        try {
            return Database.loadStudentRecords().stream()
                    .map(record -> new Student(
                            record.name(), record.nim(), record.program(), record.semester(),
                            record.score() == null ? Double.NaN : record.score()))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        } catch (java.sql.SQLException ex) {
            throw new IOException("Data mahasiswa MySQL tidak dapat dimuat: " + ex.getMessage(), ex);
        }
    }

    private static void saveStudents(List<Student> students) throws IOException {
        List<Database.StudentRecord> records = students.stream()
                .map(Main::toDatabaseStudent)
                .toList();
        try {
            Database.replaceStudentRecords(records);
        } catch (java.sql.SQLException ex) {
            throw new IOException("Data mahasiswa MySQL tidak dapat disimpan: " + ex.getMessage(), ex);
        }
    }

    private static Database.StudentRecord toDatabaseStudent(Student student) {
        return new Database.StudentRecord(
                student.nama,
                student.nim,
                student.prodi,
                student.semester,
                Double.isFinite(student.nilai) ? student.nilai : null);
    }

    private static Database.ThesisApplicationRecord toDatabaseApplication(ThesisApplication application) {
        return new Database.ThesisApplicationRecord(
                application.id,
                application.nama,
                application.nim,
                application.prodi,
                application.email,
                application.semester,
                application.judul,
                application.deskripsi,
                application.bidang,
                application.status,
                application.catatan,
                application.tanggal,
                application.similarityScore,
                application.similarityComparedTitle,
                application.similarityComparedNim,
                application.statusHistory.stream()
                        .map(history -> new Database.StatusHistoryRecord(
                                history.applicationId,
                                history.nim,
                                history.previousStatus,
                                history.newStatus,
                                history.changedAt,
                                history.note))
                        .toList());
    }

    private static List<Database.ThesisApplicationRecord> toDatabaseApplications(
            List<ThesisApplication> applications) {
        return applications.stream().map(Main::toDatabaseApplication).toList();
    }

    private static ThesisApplication fromDatabaseApplication(
            Database.ThesisApplicationRecord record) {
        ThesisApplication application = new ThesisApplication(
                record.id(),
                record.name(),
                record.nim(),
                record.program(),
                record.email(),
                record.semester(),
                record.title(),
                record.description(),
                record.field(),
                record.status(),
                record.note(),
                record.date());
        application.similarityScore = record.similarityScore();
        application.similarityComparedTitle = record.similarityComparedTitle();
        application.similarityComparedNim = record.similarityComparedNim();
        for (Database.StatusHistoryRecord history : record.statusHistory()) {
            application.statusHistory.add(new StatusHistory(
                    history.applicationId(),
                    history.nim(),
                    history.previousStatus(),
                    history.newStatus(),
                    history.changedAt(),
                    history.note()));
        }
        return application;
    }

    private static List<Student> loadStudentsFromFile() throws IOException {
        if (!Files.exists(DATA_FILE)) {
            return new ArrayList<>();
        }

        try (DataInputStream input = new DataInputStream(Files.newInputStream(DATA_FILE))) {
            if (input.readInt() != DATA_FILE_MAGIC) {
                throw new IOException("Format file data tidak dikenal.");
            }
            int count = input.readInt();
            if (count < 0 || count > 100_000) {
                throw new IOException("Jumlah data dalam file tidak valid.");
            }

            List<Student> students = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                students.add(new Student(
                        input.readUTF(),
                        input.readUTF(),
                        input.readUTF(),
                        input.readInt(),
                        input.readDouble()
                ));
            }
            if (input.read() != -1) {
                throw new IOException("File data berisi informasi tambahan yang tidak valid.");
            }
            return students;
        } catch (EOFException ex) {
            throw new IOException("File data tidak lengkap atau rusak.", ex);
        }
    }

    private static class Student {
        private final String nama;
        private final String nim;
        private final String prodi;
        private final int semester;
        private final double nilai;

        Student(String nama, String nim, String prodi, int semester, double nilai) {
            this.nama = nama;
            this.nim = nim;
            this.prodi = prodi;
            this.semester = semester;
            this.nilai = nilai;
        }
    }

    private static JPanel createResultRow(String label, String value, FormIcon icon) {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 48));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.CENTER;
        constraints.insets = new Insets(2, 2, 2, 12);

        IconBadge badge = new IconBadge(icon);
        badge.setPreferredSize(new java.awt.Dimension(40, 40));
        badge.setMinimumSize(new java.awt.Dimension(40, 40));
        badge.setMaximumSize(new java.awt.Dimension(40, 40));
        row.add(badge, constraints);

        constraints.gridx = 1;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.weightx = 0;
        constraints.insets = new Insets(3, 2, 3, 8);
        JLabel labelComponent = new JLabel(label);
        labelComponent.setFont(new Font("SansSerif", Font.BOLD, 15));
        labelComponent.setForeground(TEAL);
        row.add(labelComponent, constraints);

        constraints.gridx = 2;
        constraints.insets = new Insets(3, 8, 3, 16);
        JLabel colon = new JLabel(":");
        colon.setFont(new Font("SansSerif", Font.PLAIN, 15));
        colon.setForeground(MUTED);
        row.add(colon, constraints);

        constraints.gridx = 3;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(3, 0, 3, 2);
        JLabel valueComponent = new JLabel(value);
        valueComponent.setFont(new Font("SansSerif", Font.PLAIN, 15));
        valueComponent.setForeground(NAVY);
        row.add(valueComponent, constraints);
        return row;
    }

    private static JTextField createInput(String placeholder) {
        JTextField input = new PlaceholderTextField(placeholder);
        input.setFont(new Font("SansSerif", Font.PLAIN, 13));
        input.setForeground(NAVY);
        input.setOpaque(false);
        input.setCaretColor(TEAL);
        input.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        return input;
    }

    private static JComboBox<String> createChoice(String... options) {
        JComboBox<String> choice = new JComboBox<>(options);
        choice.setFont(new Font("SansSerif", Font.PLAIN, 13));
        choice.setForeground(NAVY);
        choice.setOpaque(false);
        choice.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        choice.setPreferredSize(new java.awt.Dimension(180, 34));
        choice.setMaximumRowCount(6);
        choice.setFocusable(true);
        choice.setUI(new BasicComboBoxUI() {
            @Override
            public void paintCurrentValueBackground(Graphics graphics, java.awt.Rectangle bounds,
                    boolean hasFocus) {
            }

            @Override
            protected JButton createArrowButton() {
                return new ChoiceArrowButton();
            }
        });
        choice.setRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(
                    JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                JLabel item = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                item.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
                item.setFont(new Font("SansSerif", Font.PLAIN, 13));
                if (index < 0) {
                    item.setOpaque(false);
                    item.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
                    item.setForeground(choice.getSelectedIndex() == 0
                            ? new Color(125, 148, 180) : NAVY);
                } else {
                    item.setOpaque(true);
                    item.setBackground(isSelected ? new Color(222, 239, 249) : Color.WHITE);
                }
                if (index >= 0 && isSelected) {
                    item.setForeground(TEAL);
                } else if (index >= 0 && value != null
                        && value.toString().startsWith("Pilih ")) {
                    item.setForeground(new Color(125, 148, 180));
                } else if (index >= 0) {
                    item.setForeground(NAVY);
                }
                return item;
            }
        });
        choice.setToolTipText("Klik untuk memilih");
        return choice;
    }

    private static JPanel createFormRow(String labelText, JComponent input, FormIcon icon) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 58));
        IconBadge badge = new IconBadge(icon);
        badge.setPreferredSize(new java.awt.Dimension(44, 44));
        badge.setMinimumSize(new java.awt.Dimension(44, 44));
        badge.setMaximumSize(new java.awt.Dimension(44, 44));
        row.add(badge, BorderLayout.WEST);

        JPanel fieldColumn = new JPanel(new BorderLayout(0, 3));
        fieldColumn.setOpaque(false);
        JLabel label = new JLabel(labelText);
        label.setFont(new Font("SansSerif", Font.BOLD, 13));
        label.setForeground(NAVY);
        fieldColumn.add(label, BorderLayout.NORTH);

        JPanel field = new RoundedFieldPanel();
        field.setLayout(new BorderLayout());
        field.setPreferredSize(new java.awt.Dimension(200, 34));
        field.add(input, BorderLayout.CENTER);
        fieldColumn.add(field, BorderLayout.CENTER);
        row.add(fieldColumn, BorderLayout.CENTER);
        return row;
    }

    private enum FormIcon {
        PERSON, ID, LOCK, GRADUATION, CALENDAR, CHART, STAR
    }

    private static class SuccessCheckIcon extends JPanel {
        SuccessCheckIcon() {
            setOpaque(false);
            setPreferredSize(new java.awt.Dimension(130, 130));
            setMinimumSize(new java.awt.Dimension(130, 130));
            setMaximumSize(new java.awt.Dimension(130, 130));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.scale(getWidth() / 174.0, getHeight() / 174.0);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(215, 235, 246));
            g2.fillOval(10, 10, 154, 154);
            g2.setColor(new Color(150, 203, 220));
            g2.fillOval(18, 18, 138, 138);
            g2.setColor(new Color(19, 132, 143));
            g2.fillOval(28, 28, 118, 118);
            g2.setColor(NAVY);
            g2.fillOval(34, 36, 106, 106);
            g2.setColor(Color.WHITE);
            g2.setStroke(new java.awt.BasicStroke(13, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            g2.drawLine(58, 88, 77, 107);
            g2.drawLine(77, 107, 113, 68);

            g2.setColor(TEAL);
            g2.setStroke(new java.awt.BasicStroke(8, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            g2.drawLine(8, 68, 25, 58);
            g2.drawLine(3, 91, 21, 91);
            g2.drawLine(10, 114, 25, 104);
            g2.drawLine(149, 58, 166, 68);
            g2.drawLine(153, 91, 171, 91);
            g2.drawLine(149, 104, 164, 114);
            g2.dispose();
        }
    }

    private static class SuccessBackgroundPanel extends JPanel {
        SuccessBackgroundPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(248, 251, 255));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 34, 34);
            g2.setColor(new Color(216, 232, 247));
            g2.fillOval(-95, -110, 300, 250);
            g2.fillOval(getWidth() - 190, getHeight() - 115, 300, 230);
            g2.fillOval(-105, getHeight() - 60, 380, 180);
            g2.setColor(new Color(19, 132, 143));
            g2.setStroke(new java.awt.BasicStroke(2.5f));
            g2.draw(new java.awt.geom.Arc2D.Double(getWidth() - 165, getHeight() - 130,
                    210, 180, 80, 120, java.awt.geom.Arc2D.OPEN));
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class ResultDetailsPanel extends JPanel {
        private boolean hovered;

        ResultDetailsPanel() {
            setOpaque(false);
        }

        void setHovered(boolean hovered) {
            this.hovered = hovered;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(hovered
                    ? new Color(225, 239, 250, 245)
                    : new Color(237, 245, 252, 240));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 26, 26);
            g2.setColor(hovered ? TEAL : new Color(195, 218, 239));
            g2.setStroke(new java.awt.BasicStroke(hovered ? 2.5f : 2));
            g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 26, 26);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class PlaceholderTextField extends JTextField {
        private final String placeholder;

        PlaceholderTextField(String placeholder) {
            this.placeholder = placeholder;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().isEmpty() && !isFocusOwner()) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setFont(getFont());
                g2.setColor(new Color(125, 148, 180));
                Insets insets = getInsets();
                g2.drawString(placeholder, insets.left,
                        (getHeight() - g2.getFontMetrics().getHeight()) / 2
                                + g2.getFontMetrics().getAscent());
                g2.dispose();
            }
        }
    }

    private static class RoundedFieldPanel extends JPanel {
        RoundedFieldPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(255, 255, 255, 238));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.setColor(new Color(192, 210, 232));
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class ChoiceArrowButton extends JButton {
        ChoiceArrowButton() {
            setPreferredSize(new java.awt.Dimension(32, 30));
            setBorderPainted(false);
            setContentAreaFilled(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (getModel().isPressed()) {
                g2.setColor(new Color(213, 230, 243));
            } else if (getModel().isRollover()) {
                g2.setColor(new Color(232, 242, 250));
            }
            if (getModel().isPressed() || getModel().isRollover()) {
                g2.fillRoundRect(1, 2, getWidth() - 3, getHeight() - 4, 12, 12);
            }
            g2.setColor(TEAL);
            g2.setStroke(new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            int centerX = getWidth() / 2;
            int centerY = getHeight() / 2 - 1;
            g2.drawLine(centerX - 4, centerY - 2, centerX, centerY + 2);
            g2.drawLine(centerX, centerY + 2, centerX + 4, centerY - 2);
            g2.dispose();
        }
    }

    private static class IconBadge extends JPanel {
        private final FormIcon icon;

        IconBadge(FormIcon icon) {
            this.icon = icon;
            setOpaque(false);
            setPreferredSize(new java.awt.Dimension(56, 56));
            setMinimumSize(new java.awt.Dimension(56, 56));
            setMaximumSize(new java.awt.Dimension(56, 56));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.scale(getWidth() / 56.0, getHeight() / 56.0);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(209, 239, 239, 235));
            g2.fillOval(0, 0, 56, 56);
            g2.setColor(new Color(12, 112, 122));
            g2.setStroke(new java.awt.BasicStroke(2.4f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            switch (icon) {
                case PERSON:
                    g2.fillOval(22, 12, 13, 13);
                    g2.fillRoundRect(16, 29, 25, 15, 8, 8);
                    break;
                case ID:
                    g2.drawRoundRect(12, 15, 32, 26, 4, 4);
                    g2.fillOval(16, 21, 8, 8);
                    g2.drawLine(15, 34, 25, 34);
                    g2.drawLine(29, 22, 40, 22);
                    g2.drawLine(29, 28, 40, 28);
                    g2.drawLine(29, 34, 40, 34);
                    break;
                case LOCK:
                    g2.drawRoundRect(17, 25, 22, 18, 4, 4);
                    g2.drawArc(21, 14, 14, 20, 0, 180);
                    g2.fillOval(26, 31, 4, 4);
                    g2.drawLine(28, 34, 28, 38);
                    break;
                case GRADUATION:
                    Path2D cap = new Path2D.Double();
                    cap.moveTo(10, 24);
                    cap.lineTo(28, 15);
                    cap.lineTo(46, 24);
                    cap.lineTo(28, 33);
                    cap.closePath();
                    g2.fill(cap);
                    g2.drawLine(18, 29, 18, 36);
                    Path2D gown = new Path2D.Double();
                    gown.moveTo(18, 34);
                    gown.quadTo(28, 43, 38, 34);
                    g2.draw(gown);
                    g2.drawLine(38, 29, 38, 36);
                    g2.drawLine(44, 25, 44, 35);
                    break;
                case CALENDAR:
                    g2.drawRoundRect(14, 15, 28, 28, 4, 4);
                    g2.drawLine(14, 23, 42, 23);
                    g2.drawLine(21, 12, 21, 19);
                    g2.drawLine(35, 12, 35, 19);
                    g2.fillOval(19, 27, 4, 4);
                    g2.fillOval(26, 27, 4, 4);
                    g2.fillOval(33, 27, 4, 4);
                    g2.fillOval(19, 33, 4, 4);
                    g2.fillOval(26, 33, 4, 4);
                    break;
                case CHART:
                    g2.fillRoundRect(14, 31, 7, 13, 2, 2);
                    g2.fillRoundRect(25, 24, 7, 20, 2, 2);
                    g2.fillRoundRect(36, 16, 7, 28, 2, 2);
                    g2.drawLine(13, 27, 23, 23);
                    g2.drawLine(23, 23, 33, 18);
                    g2.drawLine(33, 18, 42, 13);
                    break;
                case STAR:
                    Path2D star = new Path2D.Double();
                    star.moveTo(28, 10);
                    star.lineTo(33, 22);
                    star.lineTo(46, 23);
                    star.lineTo(36, 32);
                    star.lineTo(39, 45);
                    star.lineTo(28, 38);
                    star.lineTo(17, 45);
                    star.lineTo(20, 32);
                    star.lineTo(10, 23);
                    star.lineTo(23, 22);
                    star.closePath();
                    g2.draw(star);
                    break;
            }
            g2.dispose();
        }
    }

    private static class GradientPanel extends JPanel {
        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setPaint(new GradientPaint(0, 0, NAVY, getWidth(), getHeight(), TEAL));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 34, 34);
            Path2D accent = new Path2D.Double();
            accent.moveTo(getWidth() * 0.83, 0);
            accent.curveTo(getWidth() * 0.78, getHeight() * 0.42,
                    getWidth() * 0.74, getHeight() * 0.85, getWidth() * 0.65, getHeight());
            accent.lineTo(getWidth() * 0.91, getHeight());
            accent.curveTo(getWidth() * 0.97, getHeight() * 0.89,
                    getWidth() * 0.99, getHeight() * 0.54, getWidth(), 0);
            accent.closePath();
            g2.setColor(new Color(24, 181, 184, 165));
            g2.fill(accent);
            g2.setColor(new Color(255, 255, 255, 215));
            g2.setStroke(new java.awt.BasicStroke(4f));
            g2.draw(new java.awt.geom.Arc2D.Double(getWidth() * 0.63, -getHeight() * 0.5,
                    getWidth() * 0.43, getHeight() * 1.55, 248, 90,
                    java.awt.geom.Arc2D.OPEN));
            g2.dispose();
        }
    }

    private static class GlassPanel extends JPanel {
        GlassPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(29, 55, 80, 19));
            g2.fillRoundRect(2, 5, getWidth() - 4, getHeight() - 7, 24, 24);
            g2.setColor(new Color(255, 255, 255, 247));
            g2.fillRoundRect(0, 0, getWidth() - 3, getHeight() - 7, 22, 22);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class StatusCardsPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle visibleRect, int orientation, int direction) {
            return Math.max(16, visibleRect.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static class AdminMetricCardPanel extends JPanel {
        private final Color accent;

        AdminMetricCardPanel(Color accent) {
            this.accent = accent;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(24, 45, 79, 24));
            g2.fillRoundRect(2, 5, getWidth() - 4, getHeight() - 7, 22, 22);
            g2.setColor(Color.WHITE);
            g2.fillRoundRect(0, 0, getWidth() - 3, getHeight() - 7, 20, 20);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 42));
            g2.fillRoundRect(0, 0, getWidth() - 3, 7, 20, 20);
            g2.setColor(accent);
            g2.fillRoundRect(0, 3, getWidth() - 3, 4, 4, 4);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class HeroPanel extends JPanel {
        HeroPanel() {
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
        }
    }

    private static class LoginBackdrop extends JPanel {
        private final BufferedImage image;

        LoginBackdrop(BufferedImage image) {
            this.image = image;
            setOpaque(true);
            setBackground(PAGE_BG);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (image != null) {
                double scale = Math.max((double) getWidth() / image.getWidth(),
                        (double) getHeight() / image.getHeight());
                int width = (int) Math.ceil(image.getWidth() * scale);
                int height = (int) Math.ceil(image.getHeight() * scale);
                g2.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2,
                        width, height, this);
            } else {
                g2.setColor(PAGE_BG);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            g2.setColor(new Color(4, 33, 54, 46));
            g2.fillRect(0, 0, getWidth(), getHeight());
            Path2D leftPanel = new Path2D.Double();
            leftPanel.moveTo(0, 0);
            leftPanel.lineTo(getWidth() * 0.43, 0);
            leftPanel.curveTo(getWidth() * 0.36, getHeight() * 0.17,
                    getWidth() * 0.37, getHeight() * 0.31,
                    getWidth() * 0.43, getHeight() * 0.48);
            leftPanel.curveTo(getWidth() * 0.48, getHeight() * 0.67,
                    getWidth() * 0.50, getHeight() * 0.84,
                    getWidth() * 0.61, getHeight());
            leftPanel.lineTo(0, getHeight());
            leftPanel.closePath();
            g2.setPaint(new GradientPaint(0, 0, new Color(4, 35, 65, 238),
                    getWidth() * 0.58f, getHeight(), new Color(2, 83, 105, 208)));
            g2.fill(leftPanel);
            Path2D bottomWave = new Path2D.Double();
            bottomWave.moveTo(0, getHeight() * 0.88);
            bottomWave.curveTo(getWidth() * 0.15, getHeight() * 0.88,
                    getWidth() * 0.18, getHeight() * 0.98,
                    getWidth() * 0.39, getHeight() * 0.96);
            bottomWave.curveTo(getWidth() * 0.51, getHeight() * 0.95,
                    getWidth() * 0.56, getHeight() * 0.91,
                    getWidth() * 0.68, getHeight());
            bottomWave.lineTo(0, getHeight());
            bottomWave.closePath();
            g2.setPaint(new GradientPaint(0, getHeight() * 0.88f, new Color(4, 37, 83, 240),
                    getWidth() * 0.53f, getHeight(), new Color(37, 205, 200, 220)));
            g2.fill(bottomWave);
            g2.dispose();
        }
    }

    private static class DashboardBackdrop extends JPanel {
        private final BufferedImage image;

        DashboardBackdrop(BufferedImage image) {
            this.image = image;
            setOpaque(true);
            setBackground(PAGE_BG);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            if (image != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                double scale = Math.max((double) getWidth() / image.getWidth(),
                        (double) getHeight() / image.getHeight());
                int width = (int) Math.ceil(image.getWidth() * scale);
                int height = (int) Math.ceil(image.getHeight() * scale);
                g2.drawImage(image, (getWidth() - width) / 2, (getHeight() - height) / 2,
                        width, height, this);
            }
            g2.setColor(new Color(239, 246, 251, 224));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    private static class StudentHeroPanel extends JPanel {
        private final BufferedImage image;

        StudentHeroPanel(BufferedImage image) {
            this.image = image;
            setOpaque(false);
            setPreferredSize(new Dimension(0, 142));
            setBorder(BorderFactory.createEmptyBorder(22, 28, 22, 24));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            java.awt.Shape rounded = new java.awt.geom.RoundRectangle2D.Float(
                    0, 0, getWidth() - 3, getHeight() - 6, 24, 24);
            g2.setClip(rounded);
            if (image != null) {
                double scale = Math.max(
                        (double) getWidth() / image.getWidth(),
                        (double) getHeight() / image.getHeight());
                int imageWidth = (int) Math.ceil(image.getWidth() * scale);
                int imageHeight = (int) Math.ceil(image.getHeight() * scale);
                g2.drawImage(image, (getWidth() - imageWidth) / 2,
                        (getHeight() - imageHeight) / 2, imageWidth, imageHeight, this);
            } else {
                g2.setPaint(new GradientPaint(0, 0, DEEP_NAVY,
                        getWidth(), getHeight(), TEAL));
                g2.fill(rounded);
            }
            g2.setPaint(new GradientPaint(0, 0, new Color(8, 31, 65, 225),
                    getWidth(), getHeight(), new Color(10, 87, 116, 95)));
            g2.fill(rounded);
            g2.setColor(new Color(44, 211, 218));
            g2.fillRoundRect(28, 14, 118, 5, 5, 5);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class StudentQuickActionButton extends JButton {
        StudentQuickActionButton(String title, String glyph) {
            super("<html><div style='text-align:center;width:104px'>" + title + "</div></html>",
                    new StudentActionIcon(glyph));
            setFont(new Font("SansSerif", Font.BOLD, 12));
            setForeground(NAVY);
            setVerticalTextPosition(SwingConstants.BOTTOM);
            setHorizontalTextPosition(SwingConstants.CENTER);
            setIconTextGap(8);
            setBorder(BorderFactory.createEmptyBorder(11, 6, 10, 6));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(132, 106));
            setMinimumSize(new Dimension(98, 96));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(24, 45, 79, 20));
            g2.fillRoundRect(2, 5, getWidth() - 5, getHeight() - 8, 18, 18);
            g2.setPaint(getModel().isRollover()
                    ? new GradientPaint(0, 0, new Color(218, 241, 252),
                            getWidth(), getHeight(), new Color(230, 246, 253))
                    : new GradientPaint(0, 0, new Color(232, 247, 253),
                            getWidth(), getHeight(), new Color(222, 241, 251)));
            g2.fillRoundRect(0, 0, getWidth() - 4, getHeight() - 8, 18, 18);
            g2.setColor(new Color(196, 224, 242));
            g2.drawRoundRect(0, 0, getWidth() - 5, getHeight() - 9, 18, 18);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class StudentActionIcon implements Icon {
        private final String glyph;

        StudentActionIcon(String glyph) {
            this.glyph = glyph;
        }

        @Override
        public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(NAVY);
            g2.setStroke(new java.awt.BasicStroke(2.8f,
                    java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            if ("edit".equals(glyph)) {
                g2.drawLine(x + 10, y + 28, x + 27, y + 11);
                g2.drawLine(x + 8, y + 31, x + 14, y + 29);
                g2.drawLine(x + 25, y + 9, x + 30, y + 14);
                g2.drawLine(x + 9, y + 35, x + 31, y + 35);
            } else if ("search".equals(glyph)) {
                g2.drawOval(x + 5, y + 4, 21, 21);
                g2.drawLine(x + 22, y + 22, x + 34, y + 34);
            } else {
                g2.fillOval(x + 13, y + 3, 13, 13);
                g2.fillRoundRect(x + 6, y + 21, 27, 15, 13, 13);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 38;
        }

        @Override
        public int getIconHeight() {
            return 38;
        }
    }

    private static class BackgroundPanel extends JPanel {
        private final BufferedImage backgroundImage;

        BackgroundPanel(BufferedImage backgroundImage) {
            this.backgroundImage = backgroundImage;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            if (backgroundImage != null) {
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), this);
            } else {
                g2.setColor(new Color(235, 242, 247));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            g2.setColor(new Color(255, 255, 255, 148));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
        }
    }

    private static class TrashIcon implements Icon {
        private static final int SIZE = 20;

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.setStroke(new java.awt.BasicStroke(1.8f,
                    java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            g2.drawLine(x + 4, y + 5, x + 16, y + 5);
            g2.drawLine(x + 7, y + 3, x + 13, y + 3);
            g2.drawLine(x + 8, y + 2, x + 12, y + 2);
            g2.drawLine(x + 6, y + 7, x + 7, y + 17);
            g2.drawLine(x + 14, y + 7, x + 13, y + 17);
            g2.drawLine(x + 7, y + 17, x + 13, y + 17);
            g2.drawLine(x + 9, y + 8, x + 9, y + 15);
            g2.drawLine(x + 11, y + 8, x + 11, y + 15);
            g2.dispose();
        }
    }

    private static class RoundedButton extends JButton {
        private final Color backgroundColor;

        RoundedButton(String text, Color backgroundColor, Color foregroundColor) {
            super(text);
            this.backgroundColor = backgroundColor;
            setForeground(foregroundColor);
            setFont(new Font("SansSerif", Font.BOLD, 13));
            setBorder(BorderFactory.createEmptyBorder(17, 20, 17, 20));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(getModel().isRollover()
                    ? backgroundColor.darker()
                    : backgroundColor);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class SidebarButton extends JButton {
        private boolean selected;

        SidebarButton(String icon, String title) {
            super(title);
            if (Arrays.asList("home", "edit", "list", "check", "search", "logout").contains(icon)) {
                setIcon(new SidebarMenuIcon(icon));
                setIconTextGap(10);
            } else {
                setText(icon + "   " + title);
            }
            setHorizontalAlignment(SwingConstants.LEFT);
            setFont(new Font("SansSerif", Font.BOLD, 12));
            setForeground(new Color(220, 232, 243));
            setBorder(BorderFactory.createEmptyBorder(12, 10, 12, 8));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
            setPreferredSize(new Dimension(210, 46));
        }

        void setActive(boolean selected) {
            this.selected = selected;
            setForeground(selected ? Color.WHITE : new Color(211, 224, 238));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g2.setColor(TEAL);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 13, 13);
            } else if (getModel().isRollover()) {
                g2.setColor(new Color(255, 255, 255, 22));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 13, 13);
            }
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class SidebarMenuIcon implements Icon {
        private final String kind;

        SidebarMenuIcon(String kind) {
            this.kind = kind;
        }

        @Override
        public int getIconWidth() {
            return 20;
        }

        @Override
        public int getIconHeight() {
            return 20;
        }

        @Override
        public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(component.getForeground());
            g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            if ("home".equals(kind)) {
                Path2D roof = new Path2D.Double();
                roof.moveTo(2, 9);
                roof.lineTo(10, 2);
                roof.lineTo(18, 9);
                g2.draw(roof);
                g2.drawRoundRect(4, 8, 12, 10, 2, 2);
                g2.drawLine(8, 18, 8, 12);
                g2.drawLine(12, 18, 12, 12);
            } else if ("list".equals(kind)) {
                for (int row = 0; row < 3; row++) {
                    int yPosition = 4 + row * 6;
                    g2.fillOval(2, yPosition - 1, 3, 3);
                    g2.drawLine(8, yPosition, 18, yPosition);
                }
            } else if ("edit".equals(kind)) {
                g2.drawRoundRect(3, 3, 13, 14, 2, 2);
                g2.drawLine(6, 7, 12, 7);
                g2.drawLine(6, 10, 10, 10);
                g2.drawLine(11, 15, 17, 9);
                g2.drawLine(15, 8, 18, 11);
            } else if ("check".equals(kind)) {
                g2.drawRoundRect(3, 2, 14, 16, 2, 2);
                g2.drawLine(6, 6, 13, 6);
                Path2D tick = new Path2D.Double();
                tick.moveTo(6, 12);
                tick.lineTo(9, 15);
                tick.lineTo(15, 9);
                g2.draw(tick);
            } else if ("search".equals(kind)) {
                g2.drawOval(2, 2, 12, 12);
                g2.drawLine(12, 12, 19, 19);
            } else {
                g2.drawRoundRect(2, 3, 12, 14, 2, 2);
                g2.drawLine(7, 10, 18, 10);
                g2.drawLine(14, 6, 18, 10);
                g2.drawLine(14, 14, 18, 10);
            }
            g2.dispose();
        }
    }

    private static class DetailButtonRenderer extends JButton
            implements javax.swing.table.TableCellRenderer {
        DetailButtonRenderer() {
            setText("◉  Detail");
            setFont(new Font("SansSerif", Font.BOLD, 11));
            setForeground(Color.WHITE);
            setBackground(new Color(10, 91, 132));
            setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
            setFocusPainted(false);
            setOpaque(true);
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            setBackground(selected ? NAVY : new Color(10, 91, 132));
            return this;
        }
    }

    private static class DetailButtonEditor extends AbstractCellEditor
            implements javax.swing.table.TableCellEditor {
        private final JTable table;
        private final java.util.function.IntConsumer onClick;
        private final JButton button = new JButton("◉  Detail");

        DetailButtonEditor(JTable table, java.util.function.IntConsumer onClick) {
            this.table = table;
            this.onClick = onClick;
            button.setFont(new Font("SansSerif", Font.BOLD, 11));
            button.setForeground(Color.WHITE);
            button.setBackground(new Color(10, 91, 132));
            button.setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
            button.setFocusPainted(false);
            button.addActionListener(event -> {
                int viewRow = table.getEditingRow();
                int modelRow = viewRow < 0 ? -1 : table.convertRowIndexToModel(viewRow);
                fireEditingStopped();
                onClick.accept(modelRow);
            });
        }

        @Override
        public java.awt.Component getTableCellEditorComponent(
                JTable table, Object value, boolean selected, int row, int column) {
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return "Detail";
        }
    }

    private static class SearchIcon implements Icon {
        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }

        @Override
        public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.translate(x, y);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(32, 113, 139));
            g2.setStroke(new java.awt.BasicStroke(2f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            g2.drawOval(2, 2, 10, 10);
            g2.drawLine(11, 11, 16, 16);
            g2.dispose();
        }
    }

    private static class EmptyStateIcon extends JPanel {
        EmptyStateIcon() {
            setOpaque(false);
            setPreferredSize(new Dimension(64, 64));
            setMinimumSize(new Dimension(64, 64));
            setMaximumSize(new Dimension(64, 64));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(222, 241, 245));
            g2.fillOval(1, 1, 62, 62);
            g2.setColor(new Color(32, 132, 151));
            g2.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            g2.drawOval(18, 17, 22, 22);
            g2.drawLine(36, 36, 46, 46);
            g2.drawLine(24, 46, 40, 46);
            g2.dispose();
        }
    }

    private static class MetricGlyph extends JPanel {
        private final String glyph;
        private final Color accent;
        private final int size;

        MetricGlyph(String glyph, Color accent) {
            this(glyph, accent, 48);
        }

        MetricGlyph(String glyph, Color accent, int size) {
            this.glyph = glyph;
            this.accent = accent;
            this.size = size;
            setOpaque(false);
            setPreferredSize(new Dimension(size, size));
            setMinimumSize(new Dimension(size, size));
            setMaximumSize(new Dimension(size, size));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 28));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), size / 3, size / 3);
            g2.setFont(new Font("SansSerif", Font.BOLD, size * 9 / 20));
            g2.setColor(accent);
            int x = (getWidth() - g2.getFontMetrics().stringWidth(glyph)) / 2;
            int y = (getHeight() - g2.getFontMetrics().getHeight()) / 2
                    + g2.getFontMetrics().getAscent();
            g2.drawString(glyph, x, y);
            g2.dispose();
        }
    }

    private static class RoundedStatusLabel extends JLabel {
        RoundedStatusLabel(String status) {
            super(status, SwingConstants.CENTER);
            setFont(new Font("SansSerif", Font.BOLD, 11));
            setForeground(ThesisUi.statusColor(status));
            setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ThesisUi.statusTint(getText()));
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 18, 18);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static class SimilarityGauge extends JPanel {
        private double score;
        private final boolean showCaption;

        SimilarityGauge() {
            this(true);
        }

        SimilarityGauge(boolean showCaption) {
            this.showCaption = showCaption;
            setOpaque(false);
        }

        void setScore(double score) {
            this.score = Math.max(0, Math.min(1, score));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int diameter = Math.min(getWidth(), getHeight()) - (showCaption ? 24 : 12);
            if (diameter <= 0) {
                g2.dispose();
                return;
            }
            int x = (getWidth() - diameter) / 2;
            int y = (getHeight() - diameter) / 2 - (showCaption ? 7 : 0);
            float strokeWidth = Math.max(4, Math.min(11, diameter * 0.09f));
            g2.setStroke(new java.awt.BasicStroke(strokeWidth, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            g2.setColor(new Color(223, 233, 241));
            g2.drawArc(x, y, diameter, diameter, 90, -360);
            g2.setColor(score > 0.50 ? REJECTED : APPROVED);
            g2.drawArc(x, y, diameter, diameter, 90, (int) (-360 * score));

            String percentage = String.format(Locale.ROOT, "%.1f%%", score * 100);
            g2.setFont(new Font("SansSerif", Font.BOLD, Math.max(11, (int) (diameter * 0.22))));
            g2.setColor(NAVY);
            int textWidth = g2.getFontMetrics().stringWidth(percentage);
            int textY = y + diameter / 2 + g2.getFontMetrics().getAscent() / 2 - 1;
            g2.drawString(percentage, getWidth() / 2 - textWidth / 2, textY);
            if (showCaption) {
                g2.setFont(new Font("SansSerif", Font.BOLD, 11));
                g2.setColor(MUTED);
                String caption = "TINGKAT KEMIRIPAN";
                int captionWidth = g2.getFontMetrics().stringWidth(caption);
                g2.drawString(caption, getWidth() / 2 - captionWidth / 2, getHeight() - 13);
            }
            g2.dispose();
        }
    }

    private static class TableContentRenderer extends JLabel
            implements javax.swing.table.TableCellRenderer {
        private boolean badge;
        private boolean selected;
        private String status;

        TableContentRenderer() {
            setFont(new Font("SansSerif", Font.PLAIN, 12));
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 8));
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            status = value == null ? "" : value.toString();
            badge = status.equals("Disetujui") || status.equals("Ditolak")
                    || status.startsWith("Menunggu");
            selected = isSelected;
            setText(status);
            setHorizontalAlignment(badge ? SwingConstants.CENTER : SwingConstants.LEFT);
            setForeground(badge ? ThesisUi.statusColor(status) : NAVY);
            setBackground(isSelected ? table.getSelectionBackground() : Color.WHITE);
            setOpaque(!badge);
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (!badge) {
                super.paintComponent(graphics);
                return;
            }
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g2.setColor(new Color(226, 242, 243));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            java.awt.FontMetrics metrics = g2.getFontMetrics(getFont());
            int width = Math.min(getWidth() - 12, metrics.stringWidth(status) + 22);
            int height = 24;
            int x = (getWidth() - width) / 2;
            int y = (getHeight() - height) / 2;
            g2.setColor(ThesisUi.statusTint(status));
            g2.fillRoundRect(x, y, width, height, 14, 14);
            g2.setFont(getFont().deriveFont(Font.BOLD));
            g2.setColor(ThesisUi.statusColor(status));
            g2.drawString(status, x + (width - metrics.stringWidth(status)) / 2,
                    y + (height - metrics.getHeight()) / 2 + metrics.getAscent());
            g2.dispose();
        }
    }

    private static class AdminDashboardCellRenderer extends JLabel
            implements javax.swing.table.TableCellRenderer {
        private boolean statusBadge;
        private boolean selected;
        private String value = "";

        AdminDashboardCellRenderer() {
            setFont(new Font("SansSerif", Font.PLAIN, 12));
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 10));
        }

        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object cellValue, boolean isSelected, boolean hasFocus, int row, int column) {
            value = cellValue == null ? "" : cellValue.toString();
            statusBadge = value.equals("Disetujui") || value.equals("Ditolak")
                    || value.startsWith("Menunggu");
            selected = isSelected;
            setText(value);
            setHorizontalAlignment(statusBadge || column == 0 ? SwingConstants.CENTER : SwingConstants.LEFT);
            setForeground(statusBadge ? ThesisUi.statusColor(value) : NAVY);
            setBackground(isSelected ? new Color(225, 241, 248)
                    : row % 2 == 0 ? Color.WHITE : new Color(247, 250, 253));
            setOpaque(!statusBadge);
            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            if (!statusBadge) {
                super.paintComponent(graphics);
                return;
            }
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (selected) {
                g2.setColor(new Color(225, 241, 248));
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            java.awt.FontMetrics metrics = g2.getFontMetrics(getFont().deriveFont(Font.BOLD));
            int width = Math.min(getWidth() - 12, metrics.stringWidth(value) + 22);
            int height = 25;
            int x = (getWidth() - width) / 2;
            int y = (getHeight() - height) / 2;
            g2.setColor(ThesisUi.statusTint(value));
            g2.fillRoundRect(x, y, width, height, 16, 16);
            g2.setFont(getFont().deriveFont(Font.BOLD));
            g2.setColor(ThesisUi.statusColor(value));
            g2.drawString(value, x + (width - metrics.stringWidth(value)) / 2,
                    y + (height - metrics.getHeight()) / 2 + metrics.getAscent());
            g2.dispose();
        }
    }

    private static class ResetButton extends JButton {
        ResetButton() {
            super("↻  Reset");
            setFont(new Font("SansSerif", Font.BOLD, 15));
            setForeground(NAVY);
            setBorder(BorderFactory.createEmptyBorder(13, 16, 13, 16));
            setContentAreaFilled(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Kosongkan semua isian");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int arc = 18;
            if (getModel().isPressed()) {
                g2.setColor(new Color(202, 220, 237));
            } else if (getModel().isRollover()) {
                g2.setColor(new Color(225, 238, 249));
            } else {
                g2.setColor(new Color(242, 247, 252, 238));
            }
            g2.fillRoundRect(0, 1, getWidth() - 1, getHeight() - 3, arc, arc);
            g2.setColor(getModel().isRollover()
                    ? new Color(125, 164, 198)
                    : new Color(190, 210, 230));
            g2.setStroke(new java.awt.BasicStroke(1.2f));
            g2.drawRoundRect(0, 1, getWidth() - 2, getHeight() - 4, arc, arc);
            g2.dispose();
            super.paintComponent(graphics);
        }
    }

    private static ImageIcon loadLogo() {
        String fileName = "logo-unhasy-baru.png";
        try {
            BufferedImage image = null;
            var logoUrl = Main.class.getResource("/" + fileName);
            if (logoUrl != null) {
                image = ImageIO.read(logoUrl);
            }

            if (image == null) {
                Path[] candidates = {
                        Path.of("pertemuan03", fileName),
                        Path.of(fileName)
                };
                for (Path candidate : candidates) {
                    if (Files.isRegularFile(candidate)) {
                        image = ImageIO.read(candidate.toFile());
                        break;
                    }
                }
            }

            if (image == null) {
                System.err.println("Logo kampus tidak ditemukan: " + fileName);
                return null;
            }

            BufferedImage transparentImage = removeEdgeBackground(image);
            Image scaledImage = transparentImage.getScaledInstance(112, 112, Image.SCALE_SMOOTH);
            return new ImageIcon(scaledImage);
        } catch (IOException e) {
            System.err.println("Logo kampus tidak dapat dimuat: " + e.getMessage());
            return null;
        }
    }

    private static BufferedImage removeEdgeBackground(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int background = source.getRGB(0, 0);
        int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
        boolean[] visited = new boolean[pixels.length];
        ArrayDeque<Integer> pending = new ArrayDeque<>();

        for (int x = 0; x < width; x++) {
            enqueueBackgroundPixel(x, 0, width, height, background, pixels, visited, pending);
            enqueueBackgroundPixel(x, height - 1, width, height, background, pixels, visited, pending);
        }
        for (int y = 0; y < height; y++) {
            enqueueBackgroundPixel(0, y, width, height, background, pixels, visited, pending);
            enqueueBackgroundPixel(width - 1, y, width, height, background, pixels, visited, pending);
        }

        while (!pending.isEmpty()) {
            int index = pending.removeFirst();
            int x = index % width;
            int y = index / width;
            int color = pixels[index];
            int distance = colorDistance(color, background);
            int alpha = Math.min(255, distance * 255 / 75);
            pixels[index] = (color & 0x00ffffff) | (alpha << 24);

            if (x > 0) {
                enqueueBackgroundPixel(x - 1, y, width, height, background, pixels, visited, pending);
            }
            if (x + 1 < width) {
                enqueueBackgroundPixel(x + 1, y, width, height, background, pixels, visited, pending);
            }
            if (y > 0) {
                enqueueBackgroundPixel(x, y - 1, width, height, background, pixels, visited, pending);
            }
            if (y + 1 < height) {
                enqueueBackgroundPixel(x, y + 1, width, height, background, pixels, visited, pending);
            }
        }

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, width, height, pixels, 0, width);
        return result;
    }

    private static void enqueueBackgroundPixel(
            int x, int y, int width, int height, int background,
            int[] pixels, boolean[] visited, ArrayDeque<Integer> pending) {
        int index = y * width + x;
        if (!visited[index] && colorDistance(pixels[index], background) <= 75) {
            visited[index] = true;
            pending.addLast(index);
        }
    }

    private static int colorDistance(int first, int second) {
        int redDifference = ((first >> 16) & 0xff) - ((second >> 16) & 0xff);
        int greenDifference = ((first >> 8) & 0xff) - ((second >> 8) & 0xff);
        int blueDifference = (first & 0xff) - (second & 0xff);
        return (int) Math.sqrt(redDifference * redDifference
                + greenDifference * greenDifference + blueDifference * blueDifference);
    }

    private static BufferedImage loadBackgroundImage() {
        String fileName = "gedung unhasy.jpg";
        try {
            var imageUrl = Main.class.getResource("/" + fileName);
            if (imageUrl != null) {
                BufferedImage image = ImageIO.read(imageUrl);
                if (image != null) {
                    return image;
                }
            }

            Path[] candidates = {
                    Path.of(fileName),
                    Path.of("pertemuan03", fileName),
                    Path.of("src", fileName),
                    Path.of("src", "pertemuan03", fileName)
            };
            for (Path candidate : candidates) {
                if (Files.isRegularFile(candidate)) {
                    BufferedImage image = ImageIO.read(candidate.toFile());
                    if (image != null) {
                        return image;
                    }
                }
            }
            System.err.println("Background kampus tidak ditemukan: " + fileName);
        } catch (IOException e) {
            System.err.println("Background kampus tidak dapat dimuat: " + e.getMessage());
        }
        return null;
    }

    private static void createThesisWindow(String role, String nim, String adminUsername) {
        try {
            new ThesisUi(role, nim, adminUsername).show();
        } catch (IOException | java.sql.SQLException ex) {
            JOptionPane.showMessageDialog(
                    null,
                    "Data aplikasi tidak dapat dibaca: " + ex.getMessage(),
                    "Gagal membuka aplikasi",
                    JOptionPane.ERROR_MESSAGE
            );
            new LoginUi().show();
        }
    }

    private static final class LoginUi {
        private final JFrame frame = new JFrame("Login | Pendaftaran Skripsi UNHASY");
        private final JPanel formCards = new JPanel(new CardLayout());
        private final JTextField studentNim = createStyledField("NIM / Username");
        private final JPasswordField studentPassword = createPasswordField();
        private final JTextField adminUsername = createStyledField("NIM / Username");
        private final JPasswordField adminPassword = createPasswordField();
        private final JButton studentRegisterButton = new JButton("Daftar Akun Mahasiswa");
        private final JLabel accountHelp = new JLabel("Login akun mahasiswa atau daftar akun baru.");
        private final JToggleButton studentRole = createRoleButton("Mahasiswa");
        private final JToggleButton adminRole = createRoleButton("Admin");
        private String selectedRole = "Mahasiswa";

        void show() {
            buildWindow();
            frame.setVisible(true);
        }

        private void buildWindow() {
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1365, 768);
            frame.setMinimumSize(new java.awt.Dimension(1120, 700));
            frame.setLocationRelativeTo(null);

            ImageIcon logo = loadLogo();
            if (logo != null) {
                frame.setIconImage(logo.getImage());
            }

            LoginBackdrop background = new LoginBackdrop(loadBackgroundImage());
            background.setLayout(new GridBagLayout());
            background.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 60));
            background.add(createLoginContent(logo));
            frame.setContentPane(background);
        }

        private JPanel createLoginHeader(ImageIcon logo) {
            GradientPanel header = new GradientPanel();
            header.setLayout(new BorderLayout(16, 0));
            header.setBorder(BorderFactory.createEmptyBorder(12, 20, 12, 24));
            header.setPreferredSize(new java.awt.Dimension(900, 96));
            if (logo != null) {
                header.add(new JLabel(new ImageIcon(
                        logo.getImage().getScaledInstance(68, 68, Image.SCALE_SMOOTH))), BorderLayout.WEST);
            }

            JPanel brand = new JPanel();
            brand.setOpaque(false);
            brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
            JLabel university = new JLabel("UNIVERSITAS HASYIM ASY'ARI");
            university.setFont(new Font("SansSerif", Font.BOLD, 12));
            university.setForeground(new Color(220, 237, 241));
            JLabel title = new JLabel("Sistem Informasi Pendaftaran Skripsi");
            title.setFont(new Font("SansSerif", Font.BOLD, 24));
            title.setForeground(Color.WHITE);
            JLabel subtitle = new JLabel("Portal akademik mahasiswa dan administrator");
            subtitle.setFont(new Font("SansSerif", Font.PLAIN, 13));
            subtitle.setForeground(new Color(226, 239, 242));
            brand.add(university);
            brand.add(Box.createVerticalStrut(4));
            brand.add(title);
            brand.add(Box.createVerticalStrut(2));
            brand.add(subtitle);
            header.add(brand, BorderLayout.CENTER);
            return header;
        }

        private JPanel createLoginContent(ImageIcon logo) {
            JPanel layout = new JPanel(new GridBagLayout());
            layout.setOpaque(false);

            GridBagConstraints welcome = new GridBagConstraints();
            welcome.gridx = 0;
            welcome.gridy = 0;
            welcome.weightx = 1;
            welcome.weighty = 1;
            welcome.fill = GridBagConstraints.BOTH;
            welcome.insets = new Insets(0, 0, 0, 24);
            layout.add(createWelcomeCard(logo), welcome);

            GridBagConstraints login = new GridBagConstraints();
            login.gridx = 1;
            login.gridy = 0;
            login.weighty = 1;
            login.anchor = GridBagConstraints.EAST;
            login.fill = GridBagConstraints.NONE;
            layout.add(createLoginCard(), login);

            JPanel content = new JPanel(new BorderLayout());
            content.setOpaque(false);
            content.add(layout, BorderLayout.CENTER);
            return content;
        }

        private JPanel createWelcomeCard(ImageIcon logo) {
            HeroPanel card = new HeroPanel();
            card.setLayout(new BorderLayout(0, 0));
            card.setBorder(BorderFactory.createEmptyBorder(76, 48, 44, 28));

            JPanel brand = new JPanel(new BorderLayout(22, 0));
            brand.setOpaque(false);
            if (logo != null) {
                brand.add(new JLabel(new ImageIcon(
                        logo.getImage().getScaledInstance(122, 122, Image.SCALE_SMOOTH))), BorderLayout.WEST);
            }
            JLabel university = new JLabel("<html>UNIVERSITAS<br>HASYIM ASY'ARI</html>");
            university.setFont(new Font("SansSerif", Font.BOLD, 22));
            university.setForeground(Color.WHITE);
            JPanel universityBlock = new JPanel(new BorderLayout(12, 0));
            universityBlock.setOpaque(false);
            JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
            separator.setForeground(new Color(220, 244, 246, 190));
            separator.setBackground(new Color(220, 244, 246, 190));
            separator.setPreferredSize(new java.awt.Dimension(2, 122));
            universityBlock.add(separator, BorderLayout.WEST);
            university.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 0));
            universityBlock.add(university, BorderLayout.CENTER);
            brand.add(universityBlock, BorderLayout.CENTER);
            card.add(brand, BorderLayout.NORTH);

            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            JLabel titleFirstLine = new JLabel("Sistem Informasi");
            titleFirstLine.setFont(new Font("SansSerif", Font.BOLD, 42));
            titleFirstLine.setForeground(Color.WHITE);
            JLabel titleSecondLine = new JLabel("Pendaftaran Skripsi");
            titleSecondLine.setFont(new Font("SansSerif", Font.BOLD, 42));
            titleSecondLine.setForeground(new Color(48, 211, 210));
            JPanel accent = new JPanel();
            accent.setBackground(new Color(48, 211, 210));
            accent.setMaximumSize(new Dimension(440, 5));
            accent.setPreferredSize(new Dimension(440, 5));
            accent.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            JLabel description = new JLabel("<html><i>Langkah awal menuju skripsi<br>"
                    + "yang lebih baik.</i></html>");
            description.setFont(new Font("SansSerif", Font.PLAIN, 18));
            description.setForeground(new Color(225, 238, 245));
            copy.add(Box.createVerticalStrut(8));
            copy.add(titleFirstLine);
            copy.add(Box.createVerticalStrut(2));
            copy.add(titleSecondLine);
            copy.add(Box.createVerticalStrut(18));
            copy.add(accent);
            copy.add(Box.createVerticalStrut(18));
            copy.add(description);
            card.add(copy, BorderLayout.CENTER);

            JPanel features = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0));
            features.setOpaque(false);
            features.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            features.add(createLoginFeature(new RoleIcon("Mahasiswa"), "Mudah\n& Cepat"));
            features.add(createFeatureSeparator());
            features.add(createLoginFeature(new RoleIcon("Admin"), "Aman\n& Terpercaya"));
            features.add(createFeatureSeparator());
            features.add(createLoginFeature(createMonitorIcon(), "Terintegrasi\ndengan Akademik"));
            card.add(features, BorderLayout.SOUTH);

            return card;
        }

        private Icon createMonitorIcon() {
            return new Icon() {
                @Override
                public int getIconWidth() {
                    return 30;
                }

                @Override
                public int getIconHeight() {
                    return 30;
                }

                @Override
                public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
                    Graphics2D g2 = (Graphics2D) graphics.create();
                    g2.translate(x, y);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(91, 220, 218));
                    g2.setStroke(new java.awt.BasicStroke(2.2f, java.awt.BasicStroke.CAP_ROUND,
                            java.awt.BasicStroke.JOIN_ROUND));
                    g2.drawRoundRect(2, 4, 25, 18, 3, 3);
                    g2.drawLine(14, 23, 14, 27);
                    g2.drawLine(9, 27, 20, 27);
                    g2.dispose();
                }
            };
        }

        private JPanel createLoginFeature(Icon icon, String text) {
            JPanel feature = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            feature.setOpaque(false);
            JLabel iconLabel = new JLabel(icon);
            iconLabel.setForeground(new Color(91, 220, 218));
            JLabel textLabel = new JLabel("<html>" + text.replace("\n", "<br>") + "</html>");
            textLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
            textLabel.setForeground(new Color(237, 247, 250));
            feature.add(iconLabel);
            feature.add(textLabel);
            return feature;
        }

        private JSeparator createFeatureSeparator() {
            JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
            separator.setForeground(new Color(220, 244, 246, 190));
            separator.setBackground(new Color(220, 244, 246, 190));
            separator.setPreferredSize(new Dimension(1, 38));
            return separator;
        }

        private JPanel createLoginCard() {
            LoginCardPanel card = new LoginCardPanel();
            card.setPreferredSize(new java.awt.Dimension(508, 572));
            card.setLayout(new BorderLayout(0, 16));
            card.setBorder(BorderFactory.createEmptyBorder(30, 34, 28, 34));

            JPanel heading = new JPanel();
            heading.setOpaque(false);
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            AcademicMark academicMark = new AcademicMark(76);
            academicMark.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            JLabel title = new JLabel("Selamat Datang");
            title.setFont(new Font("SansSerif", Font.BOLD, 30));
            title.setForeground(NAVY);
            title.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            JLabel subtitle = new JLabel("Masuk untuk melanjutkan");
            subtitle.setFont(new Font("SansSerif", Font.PLAIN, 16));
            subtitle.setForeground(MUTED);
            subtitle.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            heading.add(academicMark);
            heading.add(Box.createVerticalStrut(12));
            heading.add(title);
            heading.add(Box.createVerticalStrut(6));
            heading.add(subtitle);
            card.add(heading, BorderLayout.NORTH);

            JPanel body = new JPanel();
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            JPanel roleChooser = new JPanel(new GridLayout(1, 2, 14, 0));
            roleChooser.setOpaque(false);
            ButtonGroup roles = new ButtonGroup();
            roles.add(studentRole);
            roles.add(adminRole);
            studentRole.setSelected(true);
            roleChooser.add(studentRole);
            roleChooser.add(adminRole);
            studentRole.addActionListener(e -> changeRole("Mahasiswa"));
            adminRole.addActionListener(e -> changeRole("Admin"));

            formCards.setOpaque(false);
            formCards.add(createStudentLoginForm(), "Mahasiswa");
            formCards.add(createAdminLoginForm(), "Admin");
            formCards.setPreferredSize(new java.awt.Dimension(400, 116));

            body.add(roleChooser);
            body.add(Box.createVerticalStrut(22));
            body.add(formCards);
            card.add(body, BorderLayout.CENTER);

            JButton login = new LoginGradientButton("Masuk  →");
            login.setFont(new Font("SansSerif", Font.BOLD, 17));
            login.setBorder(BorderFactory.createEmptyBorder(15, 18, 15, 18));
            login.addActionListener(e -> authenticate());
            JPanel bottom = new JPanel(new BorderLayout(0, 5));
            bottom.setOpaque(false);
            bottom.add(login, BorderLayout.NORTH);
            studentRegisterButton.setFont(new Font("SansSerif", Font.BOLD, 13));
            studentRegisterButton.setForeground(TEAL);
            studentRegisterButton.setContentAreaFilled(false);
            studentRegisterButton.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            studentRegisterButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            studentRegisterButton.addActionListener(e -> showStudentRegistrationDialog());
            bottom.add(studentRegisterButton, BorderLayout.CENTER);
            accountHelp.setFont(new Font("SansSerif", Font.PLAIN, 13));
            accountHelp.setForeground(NAVY);
            accountHelp.setHorizontalAlignment(SwingConstants.CENTER);
            bottom.add(accountHelp, BorderLayout.SOUTH);
            card.add(bottom, BorderLayout.SOUTH);
            frame.getRootPane().setDefaultButton(login);
            return card;
        }

        private JPanel createStudentLoginForm() {
            JPanel form = new JPanel();
            form.setOpaque(false);
            form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
            form.add(createFieldShell(studentNim, LoginFieldIcon.USER));
            form.add(Box.createVerticalStrut(12));
            form.add(createPasswordShell(studentPassword));
            return form;
        }

        private JPanel createAdminLoginForm() {
            JPanel form = new JPanel();
            form.setOpaque(false);
            form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
            form.add(createFieldShell(adminUsername, LoginFieldIcon.USER));
            form.add(Box.createVerticalStrut(12));
            form.add(createPasswordShell(adminPassword));
            return form;
        }

        private void showStudentRegistrationDialog() {
            JDialog dialog = new JDialog(frame, "Daftar Akun Mahasiswa", true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

            JTextField nimField = createStyledField("NIM");
            JTextField nameField = createStyledField("Nama lengkap");
            JComboBox<String> programField = new JComboBox<>(new String[]{
                "Pilih program studi",
                "Sistem Informasi",
                "Teknik Informatika",
                "Pendidikan Agama Islam",
                "Pendidikan Guru Sekolah Dasar",
                "Ekonomi Syariah",
                "Hukum Keluarga Islam",
                "Manajemen Pendidikan Islam",
                "Teknologi Informasi"
            });
            programField.setFont(new Font("SansSerif", Font.PLAIN, 15));
            programField.setBackground(Color.WHITE);
            String[] semesterOptions = new String[15];
            semesterOptions[0] = "Pilih semester";
            for (int semester = 1; semester < semesterOptions.length; semester++) {
                semesterOptions[semester] = "Semester " + semester;
            }
            JComboBox<String> semesterField = new JComboBox<>(semesterOptions);
            semesterField.setFont(new Font("SansSerif", Font.PLAIN, 15));
            semesterField.setBackground(Color.WHITE);
            JTextField emailField = createStyledField("nama@email.com");
            JPasswordField passwordField = createPasswordField();
            JPasswordField confirmPasswordField = createPasswordField();

            JPanel fields = new JPanel();
            fields.setOpaque(false);
            fields.setLayout(new BoxLayout(fields, BoxLayout.Y_AXIS));
            fields.add(createRegistrationField("NIM", createFieldShell(nimField)));
            fields.add(Box.createVerticalStrut(10));
            fields.add(createRegistrationField("Nama Lengkap", createFieldShell(nameField)));
            fields.add(Box.createVerticalStrut(10));
            fields.add(createRegistrationField("Program Studi", createFieldShell(programField)));
            fields.add(Box.createVerticalStrut(10));
            fields.add(createRegistrationField("Semester", createFieldShell(semesterField)));
            fields.add(Box.createVerticalStrut(10));
            fields.add(createRegistrationField("Email", createFieldShell(emailField)));
            fields.add(Box.createVerticalStrut(10));
            fields.add(createRegistrationField("Password (minimal 8 karakter)",
                    createPasswordShell(passwordField)));
            fields.add(Box.createVerticalStrut(10));
            fields.add(createRegistrationField("Konfirmasi Password",
                    createPasswordShell(confirmPasswordField)));

            JPanel content = new JPanel(new BorderLayout(0, 12));
            content.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
            content.setBackground(new Color(247, 250, 252));
            JLabel hint = new JLabel(
                    "Data NIM lama akan dicocokkan agar akun terhubung ke profil/pengajuan yang benar.");
            hint.setFont(new Font("SansSerif", Font.PLAIN, 12));
            content.add(hint, BorderLayout.NORTH);
            JScrollPane fieldsScroll = new JScrollPane(fields);
            fieldsScroll.setBorder(BorderFactory.createEmptyBorder());
            fieldsScroll.setOpaque(false);
            fieldsScroll.getViewport().setOpaque(false);
            fieldsScroll.getVerticalScrollBar().setUnitIncrement(16);
            content.add(fieldsScroll, BorderLayout.CENTER);

            JButton register = new LoginGradientButton("Daftar Akun");
            register.setFont(new Font("SansSerif", Font.BOLD, 15));
            register.setBorder(BorderFactory.createEmptyBorder(12, 18, 12, 18));
            JButton cancel = new JButton("Batal");
            cancel.addActionListener(e -> dialog.dispose());
            JPanel actions = new JPanel(new BorderLayout(10, 0));
            actions.setOpaque(false);
            actions.add(cancel, BorderLayout.WEST);
            actions.add(register, BorderLayout.CENTER);
            content.add(actions, BorderLayout.SOUTH);

            register.addActionListener(e -> {
                String nim = nimField.getText().trim();
                String name = nameField.getText().trim();
                String program = programField.getSelectedIndex() == 0
                        ? "" : String.valueOf(programField.getSelectedItem());
                String semesterValue = semesterField.getSelectedIndex() == 0
                        ? "" : String.valueOf(semesterField.getSelectedItem());
                String email = emailField.getText().trim();
                char[] password = passwordField.getPassword();
                char[] confirmation = confirmPasswordField.getPassword();
                try {
                    if (nim.isEmpty() || name.isEmpty() || program.isEmpty()
                            || semesterValue.isEmpty() || email.isEmpty()
                            || password.length == 0 || confirmation.length == 0) {
                        showRegistrationError(dialog, "Lengkapi semua data pendaftaran akun mahasiswa.");
                        return;
                    }
                    if (!email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                        showRegistrationError(dialog, "Format email belum valid.");
                        return;
                    }
                    if (password.length < 8) {
                        showRegistrationError(dialog, "Password harus memiliki minimal 8 karakter.");
                        return;
                    }
                    if (!Arrays.equals(password, confirmation)) {
                        showRegistrationError(dialog, "Password dan konfirmasi password tidak sama.");
                        return;
                    }

                    int semester = Integer.parseInt(semesterValue.replaceAll("\\D+", ""));
                    validateLegacyStudentProfile(nim, name, program, semester);
                    Database.StudentRegistrationResult result = Database.registerStudent(
                            nim, name, program, semester, email, password);
                    if (result == Database.StudentRegistrationResult.ACCOUNT_EXISTS) {
                        showRegistrationError(dialog, "NIM ini sudah memiliki akun. Silakan login.");
                        return;
                    }

                    studentNim.setText(nim);
                    studentPassword.setText("");
                    dialog.dispose();
                    JOptionPane.showMessageDialog(
                            frame,
                            "Pendaftaran berhasil. Silakan login menggunakan NIM dan password Anda.",
                            "Akun Berhasil Dibuat",
                            JOptionPane.INFORMATION_MESSAGE);
                } catch (IOException | java.sql.SQLException | NumberFormatException ex) {
                    showRegistrationError(dialog, "Akun tidak dapat didaftarkan: " + ex.getMessage());
                } finally {
                    Arrays.fill(password, '\0');
                    Arrays.fill(confirmation, '\0');
                    passwordField.setText("");
                    confirmPasswordField.setText("");
                }
            });

            dialog.setContentPane(content);
            dialog.setSize(540, 700);
            dialog.setMinimumSize(new java.awt.Dimension(500, 600));
            dialog.setLocationRelativeTo(frame);
            dialog.setVisible(true);
        }

        private void showRegistrationError(JDialog dialog, String message) {
            JOptionPane.showMessageDialog(
                    dialog, message, "Pendaftaran Tidak Berhasil", JOptionPane.ERROR_MESSAGE);
        }

        private JPanel createRegistrationField(String captionText, JComponent input) {
            JPanel field = new JPanel(new BorderLayout(0, 5));
            field.setOpaque(false);
            JLabel caption = createCaption(captionText);
            field.add(caption, BorderLayout.NORTH);
            field.add(input, BorderLayout.CENTER);
            field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 78));
            field.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            return field;
        }

        private void changeRole(String role) {
            selectedRole = role;
            ((CardLayout) formCards.getLayout()).show(formCards, role);
            studentRegisterButton.setVisible("Mahasiswa".equals(role));
            accountHelp.setText("Mahasiswa".equals(role)
                    ? "Login akun mahasiswa atau daftar akun baru."
                    : "<html>Belum punya akun? <b><font color='#087f91'>Hubungi Admin</font></b></html>");
        }

        private void authenticate() {
            String nim = studentNim.getText().trim();
            String username = adminUsername.getText().trim();
            char[] password = "Admin".equals(selectedRole)
                    ? adminPassword.getPassword() : studentPassword.getPassword();
            boolean valid;
            if ("Admin".equals(selectedRole)) {
                if (username.isEmpty() || password.length == 0) {
                    Arrays.fill(password, '\0');
                    showLoginError("Masukkan username dan password admin.");
                    return;
                }
            } else {
                if (nim.isEmpty() || password.length == 0) {
                    Arrays.fill(password, '\0');
                    showLoginError("Masukkan NIM dan password mahasiswa.");
                    return;
                }
            }

            try {
                valid = "Admin".equals(selectedRole)
                        ? authenticateAdmin(username, password)
                        : Database.authenticateStudent(nim, password);
            } catch (java.sql.SQLException ex) {
                showLoginError(("Admin".equals(selectedRole)
                        ? "Login admin tidak dapat diverifikasi: "
                        : "Login mahasiswa tidak dapat diverifikasi: ") + ex.getMessage());
                return;
            } finally {
                Arrays.fill(password, '\0');
            }

            if (!valid) {
                showLoginError("Admin".equals(selectedRole)
                        ? "Username atau password admin salah."
                        : "NIM atau password mahasiswa salah.");
                return;
            }

            showLoginSuccessDialog(selectedRole);
            frame.dispose();
            createThesisWindow(selectedRole, "Mahasiswa".equals(selectedRole) ? nim : "", username);
        }

        private void showLoginSuccessDialog(String role) {
            JDialog dialog = new JDialog(frame, "Login Berhasil", true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            dialog.setUndecorated(true);
            LoginSuccessPanel content = new LoginSuccessPanel();
            content.setLayout(new BorderLayout(0, 8));
            content.setBorder(BorderFactory.createEmptyBorder(22, 34, 28, 34));

            JPanel closeRow = new JPanel(new BorderLayout());
            closeRow.setOpaque(false);
            JButton close = new JButton("×");
            close.setFont(new Font("SansSerif", Font.PLAIN, 25));
            close.setForeground(new Color(75, 101, 125));
            close.setBorderPainted(false);
            close.setContentAreaFilled(false);
            close.setFocusPainted(false);
            close.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            close.setToolTipText("Tutup");
            close.addActionListener(event -> dialog.dispose());
            closeRow.add(close, BorderLayout.EAST);
            content.add(closeRow, BorderLayout.NORTH);

            JPanel center = new JPanel();
            center.setOpaque(false);
            center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
            LoginSuccessCheckIcon check = new LoginSuccessCheckIcon();
            check.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

            JLabel title = new JLabel("Login Berhasil!");
            title.setFont(new Font("SansSerif", Font.BOLD, 27));
            title.setForeground(NAVY);
            title.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

            JLabel roleLabel = new JLabel(role, SwingConstants.CENTER);
            roleLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
            roleLabel.setForeground(new Color(7, 105, 130));
            JPanel roleBadge = new LoginRolePill();
            roleBadge.setLayout(new GridBagLayout());
            roleBadge.add(roleLabel);
            roleBadge.setPreferredSize(new Dimension(128, 38));
            roleBadge.setMaximumSize(new Dimension(128, 38));
            roleBadge.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

            JSeparator divider = new JSeparator();
            divider.setForeground(new Color(220, 232, 241));
            divider.setBackground(new Color(220, 232, 241));
            divider.setMaximumSize(new Dimension(330, 1));
            divider.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

            JLabel welcome = new JLabel("Selamat datang di");
            welcome.setFont(new Font("SansSerif", Font.BOLD, 15));
            welcome.setForeground(NAVY);
            welcome.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

            JLabel message = new JLabel(
                    "<html><div style='text-align:center'>Sistem Informasi Pendaftaran Skripsi UNHASY.</div></html>");
            message.setFont(new Font("SansSerif", Font.PLAIN, 14));
            message.setForeground(NAVY);
            message.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);

            center.add(check);
            center.add(Box.createVerticalStrut(4));
            center.add(title);
            center.add(Box.createVerticalStrut(8));
            center.add(roleBadge);
            center.add(Box.createVerticalStrut(10));
            center.add(divider);
            center.add(Box.createVerticalStrut(12));
            center.add(welcome);
            center.add(Box.createVerticalStrut(4));
            center.add(message);
            content.add(center, BorderLayout.CENTER);

            JButton continueButton = new LoginSuccessButton("Lanjutkan  →");
            continueButton.setFont(new Font("SansSerif", Font.BOLD, 16));
            continueButton.setBorder(BorderFactory.createEmptyBorder(14, 52, 14, 52));
            continueButton.addActionListener(event -> dialog.dispose());
            JPanel actions = new JPanel(new BorderLayout());
            actions.setOpaque(false);
            actions.add(continueButton);
            content.add(actions, BorderLayout.SOUTH);

            dialog.setContentPane(content);
            dialog.getRootPane().setDefaultButton(continueButton);
            dialog.setSize(476, 460);
            dialog.setLocationRelativeTo(null);

            java.awt.Component previousGlassPane = frame.getGlassPane();
            boolean previousGlassPaneVisible = previousGlassPane.isVisible();
            JPanel overlay = new LoginDimOverlay();
            frame.setGlassPane(overlay);
            overlay.setVisible(true);
            try {
                dialog.setVisible(true);
            } finally {
                frame.setGlassPane(previousGlassPane);
                previousGlassPane.setVisible(previousGlassPaneVisible);
            }
        }

        private static final class LoginDimOverlay extends JPanel {
            LoginDimOverlay() {
                setOpaque(false);
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setColor(new Color(3, 17, 34, 150));
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        }

        private static final class LoginSuccessPanel extends JPanel {
            LoginSuccessPanel() {
                setOpaque(false);
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                for (int i = 9; i >= 1; i--) {
                    g2.setColor(new Color(8, 28, 48, 4 + (9 - i)));
                    g2.fillRoundRect(i, i + 5, getWidth() - i * 2, getHeight() - i * 2 - 5, 28, 28);
                }
                g2.setColor(Color.WHITE);
                g2.fillRoundRect(10, 4, getWidth() - 20, getHeight() - 20, 26, 26);
                g2.setColor(new Color(227, 236, 244));
                g2.drawRoundRect(10, 4, getWidth() - 21, getHeight() - 21, 26, 26);
                g2.dispose();
                super.paintComponent(graphics);
            }
        }

        private static final class LoginRolePill extends JPanel {
            LoginRolePill() {
                setOpaque(false);
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(220, 244, 246));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(graphics);
            }
        }

        private static final class LoginSuccessCheckIcon extends JPanel {
            LoginSuccessCheckIcon() {
                setOpaque(false);
                setPreferredSize(new Dimension(112, 112));
                setMinimumSize(new Dimension(112, 112));
                setMaximumSize(new Dimension(112, 112));
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(217, 245, 245));
                g2.fillOval(3, 3, 106, 106);
                g2.setColor(new Color(165, 226, 229));
                g2.fillOval(9, 9, 94, 94);
                g2.setPaint(new GradientPaint(22, 23, new Color(9, 176, 166),
                        88, 88, new Color(4, 53, 91)));
                g2.fillOval(16, 16, 80, 80);
                g2.setColor(Color.WHITE);
                g2.setStroke(new java.awt.BasicStroke(8, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                g2.drawLine(37, 57, 49, 69);
                g2.drawLine(49, 69, 74, 43);
                g2.dispose();
            }
        }

        private static final class LoginSuccessButton extends JButton {
            LoginSuccessButton(String text) {
                super(text);
                setForeground(Color.WHITE);
                setContentAreaFilled(false);
                setFocusPainted(false);
                setBorderPainted(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(8, 42, 67, 45));
                g2.fillRoundRect(1, 4, getWidth() - 2, getHeight() - 3, getHeight(), getHeight());
                Color start = getModel().isRollover()
                        ? new Color(10, 117, 145) : new Color(5, 58, 103);
                Color end = getModel().isRollover()
                        ? new Color(12, 179, 167) : new Color(5, 177, 166);
                g2.setPaint(new GradientPaint(0, 0, start, getWidth(), getHeight(), end));
                g2.fillRoundRect(0, 0, getWidth(), getHeight() - 4, getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(graphics);
            }
        }

        private void showLoginError(String message) {
            JOptionPane.showMessageDialog(
                    frame, message, "Login Gagal", JOptionPane.ERROR_MESSAGE);
        }

        private static JLabel createCaption(String text) {
            JLabel label = new JLabel(text);
            label.setFont(new Font("SansSerif", Font.BOLD, 12));
            label.setForeground(NAVY);
            label.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            return label;
        }

        private static JTextField createStyledField(String placeholder) {
            JTextField field = createInput(placeholder);
            field.setFont(new Font("SansSerif", Font.PLAIN, 15));
            return field;
        }

        private static JPasswordField createPasswordField() {
            JPasswordField field = new JPasswordField() {
                @Override
                protected void paintComponent(Graphics graphics) {
                    super.paintComponent(graphics);
                    if (getDocument().getLength() == 0) {
                        Graphics2D g2 = (Graphics2D) graphics.create();
                        g2.setColor(new Color(111, 137, 160));
                        g2.setFont(getFont());
                        java.awt.FontMetrics metrics = g2.getFontMetrics();
                        int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
                        g2.drawString("Password", getInsets().left + 2, y);
                        g2.dispose();
                    }
                }
            };
            field.setFont(new Font("SansSerif", Font.PLAIN, 15));
            field.setForeground(NAVY);
            field.setOpaque(false);
            field.setCaretColor(TEAL);
            field.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 8));
            field.getAccessibleContext().setAccessibleName("Password");
            return field;
        }

        private static JPanel createFieldShell(JComponent input) {
            return createFieldShell(input, LoginFieldIcon.NONE);
        }

        private static JPanel createFieldShell(JComponent input, LoginFieldIcon icon) {
            JPanel shell = new RoundedFieldPanel();
            shell.setLayout(new BorderLayout(8, 0));
            shell.setPreferredSize(new java.awt.Dimension(400, 52));
            shell.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 52));
            if (icon != LoginFieldIcon.NONE) {
                shell.add(new LoginFieldMark(icon), BorderLayout.WEST);
            }
            shell.add(input, BorderLayout.CENTER);
            return shell;
        }

        private static JPanel createPasswordShell(JPasswordField password) {
            JPanel shell = new RoundedFieldPanel();
            shell.setLayout(new BorderLayout(8, 0));
            shell.setPreferredSize(new java.awt.Dimension(400, 52));
            shell.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, 52));
            shell.add(new LoginFieldMark(LoginFieldIcon.LOCK), BorderLayout.WEST);
            shell.add(password, BorderLayout.CENTER);
            char echoChar = password.getEchoChar();
            JToggleButton visibility = new JToggleButton(new EyeIcon());
            visibility.setToolTipText("Tampilkan password");
            visibility.getAccessibleContext().setAccessibleName("Tampilkan atau sembunyikan password");
            visibility.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 12));
            visibility.setContentAreaFilled(false);
            visibility.setFocusPainted(false);
            visibility.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            visibility.addActionListener(e -> {
                password.setEchoChar(visibility.isSelected() ? (char) 0 : echoChar);
                visibility.setToolTipText(visibility.isSelected()
                        ? "Sembunyikan password" : "Tampilkan password");
                visibility.repaint();
            });
            shell.add(visibility, BorderLayout.EAST);
            return shell;
        }

        private enum LoginFieldIcon {
            NONE, USER, LOCK
        }

        private static final class LoginCardPanel extends JPanel {
            LoginCardPanel() {
                setOpaque(false);
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(6, 28, 48, 34));
                g2.fillRoundRect(4, 9, getWidth() - 8, getHeight() - 12, 30, 30);
                g2.setColor(new Color(255, 255, 255, 246));
                g2.fillRoundRect(1, 1, getWidth() - 4, getHeight() - 10, 30, 30);
                g2.setColor(new Color(255, 255, 255, 180));
                g2.drawRoundRect(1, 1, getWidth() - 5, getHeight() - 11, 30, 30);
                g2.dispose();
                super.paintComponent(graphics);
            }
        }

        private static final class LoginGradientButton extends JButton {
            LoginGradientButton(String text) {
                super(text);
                setForeground(Color.WHITE);
                setContentAreaFilled(false);
                setFocusPainted(false);
                setBorderPainted(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color start = getModel().isRollover()
                        ? new Color(10, 106, 132) : new Color(8, 101, 127);
                Color end = getModel().isRollover()
                        ? new Color(8, 157, 157) : new Color(19, 174, 170);
                g2.setPaint(new GradientPaint(0, 0, start, getWidth(), getHeight(), end));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 22, 22);
                g2.dispose();
                super.paintComponent(graphics);
            }
        }

        private static final class AcademicMark extends JPanel {
            private final int size;

            AcademicMark(int size) {
                this.size = size;
                setOpaque(false);
                setPreferredSize(new java.awt.Dimension(size, size));
                setMinimumSize(new java.awt.Dimension(size, size));
                setMaximumSize(new java.awt.Dimension(size, size));
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double scale = size / 84.0;
                g2.scale(scale, scale);
                g2.setColor(new Color(226, 248, 247));
                g2.fillOval(2, 2, 80, 80);
                g2.setColor(new Color(19, 132, 143, 45));
                g2.fillOval(9, 9, 66, 66);
                g2.setColor(NAVY);
                Path2D cap = new Path2D.Double();
                cap.moveTo(18, 37);
                cap.lineTo(42, 25);
                cap.lineTo(66, 37);
                cap.lineTo(42, 49);
                cap.closePath();
                g2.fill(cap);
                g2.fillRoundRect(27, 43, 30, 12, 4, 4);
                g2.setColor(new Color(19, 132, 143));
                g2.setStroke(new java.awt.BasicStroke(2.5f));
                g2.drawLine(65, 38, 65, 55);
                g2.setColor(new Color(232, 174, 54));
                g2.fillOval(62, 54, 6, 8);
                g2.dispose();
            }
        }

        private static final class LoginFieldMark extends JPanel {
            private final LoginFieldIcon icon;

            LoginFieldMark(LoginFieldIcon icon) {
                this.icon = icon;
                setOpaque(false);
                setPreferredSize(new java.awt.Dimension(48, 52));
                setMinimumSize(new java.awt.Dimension(48, 52));
                setMaximumSize(new java.awt.Dimension(48, 52));
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(18, 91, 119));
                g2.setStroke(new java.awt.BasicStroke(2.4f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                if (icon == LoginFieldIcon.USER) {
                    g2.fillOval(19, 11, 12, 12);
                    g2.fillRoundRect(14, 26, 22, 14, 9, 9);
                } else if (icon == LoginFieldIcon.LOCK) {
                    g2.drawRoundRect(15, 21, 20, 17, 4, 4);
                    g2.drawArc(19, 11, 12, 20, 0, 180);
                    g2.fillOval(23, 27, 4, 4);
                    g2.drawLine(25, 30, 25, 34);
                }
                g2.dispose();
            }
        }

        private static final class RoleIcon implements Icon {
            private final boolean student;

            RoleIcon(String role) {
                student = "Mahasiswa".equals(role);
            }

            @Override
            public int getIconWidth() {
                return 22;
            }

            @Override
            public int getIconHeight() {
                return 22;
            }

            @Override
            public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color iconColor = component.isEnabled() ? component.getForeground() : MUTED;
                g2.setColor(iconColor);
                if (student) {
                    Path2D cap = new Path2D.Double();
                    cap.moveTo(1, 8);
                    cap.lineTo(11, 3);
                    cap.lineTo(21, 8);
                    cap.lineTo(11, 13);
                    cap.closePath();
                    g2.fill(cap);
                    g2.fillRoundRect(6, 12, 10, 5, 2, 2);
                    g2.drawLine(20, 9, 20, 16);
                } else {
                    Path2D shield = new Path2D.Double();
                    shield.moveTo(11, 2);
                    shield.lineTo(20, 5);
                    shield.lineTo(19, 13);
                    shield.curveTo(18, 17, 14, 20, 11, 21);
                    shield.curveTo(8, 20, 4, 17, 3, 13);
                    shield.lineTo(2, 5);
                    shield.closePath();
                    g2.fill(shield);
                    g2.setColor(component.getBackground());
                    g2.fillRect(10, 6, 2, 9);
                    g2.fillRect(7, 9, 8, 2);
                }
                g2.dispose();
            }
        }

        private static final class EyeIcon implements Icon {
            @Override
            public int getIconWidth() {
                return 22;
            }

            @Override
            public int getIconHeight() {
                return 22;
            }

            @Override
            public void paintIcon(java.awt.Component component, Graphics graphics, int x, int y) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(82, 113, 137));
                g2.setStroke(new java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                Path2D eye = new Path2D.Double();
                eye.moveTo(2, 11);
                eye.quadTo(11, 1, 20, 11);
                eye.quadTo(11, 21, 2, 11);
                g2.draw(eye);
                g2.drawOval(8, 8, 6, 6);
                if (component instanceof AbstractButton
                        && !((AbstractButton) component).isSelected()) {
                    g2.drawLine(3, 19, 19, 3);
                }
                g2.dispose();
            }
        }

        private static JToggleButton createRoleButton(String text) {
            JToggleButton button = new RoleToggleButton(text);
            button.setFont(new Font("SansSerif", Font.BOLD, 15));
            button.setIcon(new RoleIcon(text));
            button.setIconTextGap(12);
            button.setForeground(NAVY);
            button.setBackground(new Color(244, 249, 251));
            button.setPreferredSize(new java.awt.Dimension(200, 56));
            button.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
            button.addChangeListener(e -> {
                button.setForeground(button.isSelected() ? Color.WHITE : NAVY);
                button.setBackground(button.isSelected()
                        ? new Color(8, 118, 143) : new Color(244, 249, 251));
            });
            return button;
        }

        private static final class RoleToggleButton extends JToggleButton {
            RoleToggleButton(String text) {
                super(text);
                setContentAreaFilled(false);
                setFocusPainted(false);
                setBorderPainted(false);
                setOpaque(false);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int arc = getHeight();
                if (isSelected()) {
                    g2.setColor(new Color(0, 62, 82, 35));
                    g2.fillRoundRect(1, 3, getWidth() - 2, getHeight() - 3, arc, arc);
                    g2.setPaint(new GradientPaint(0, 0, new Color(8, 118, 143),
                            getWidth(), getHeight(), new Color(13, 165, 163)));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight() - 3, arc, arc);
                } else {
                    g2.setColor(new Color(255, 255, 255, 155));
                    g2.fillRoundRect(0, 0, getWidth(), getHeight() - 3, arc, arc);
                    g2.setColor(getModel().isRollover()
                            ? new Color(19, 151, 166) : new Color(154, 204, 220));
                    g2.setStroke(new java.awt.BasicStroke(1.5f));
                    g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 5, arc, arc);
                }
                g2.dispose();
                super.paintComponent(graphics);
            }
        }
    }

    private static final class ThesisApplication {
        private final String id;
        private String nama;
        private String nim;
        private String prodi;
        private String email;
        private String semester;
        private String judul;
        private String deskripsi;
        private String bidang;
        private String status;
        private String catatan;
        private final String tanggal;
        private Double similarityScore;
        private String similarityComparedTitle;
        private String similarityComparedNim;
        private final List<StatusHistory> statusHistory = new ArrayList<>();

        ThesisApplication(
                String id, String nama, String nim, String prodi, String email, String semester,
                String judul, String deskripsi, String bidang, String status, String catatan, String tanggal) {
            this.id = id;
            this.nama = nama;
            this.nim = nim;
            this.prodi = prodi;
            this.email = email;
            this.semester = semester;
            this.judul = judul;
            this.deskripsi = deskripsi;
            this.bidang = bidang;
            this.status = status;
            this.catatan = catatan;
            this.tanggal = tanggal;
        }
    }

    private static final class StatusHistory {
        private final String applicationId;
        private final String nim;
        private final String previousStatus;
        private final String newStatus;
        private final String changedAt;
        private final String note;

        private StatusHistory(
                String applicationId, String nim, String previousStatus, String newStatus,
                String changedAt, String note) {
            this.applicationId = applicationId;
            this.nim = nim;
            this.previousStatus = previousStatus;
            this.newStatus = newStatus;
            this.changedAt = changedAt;
            this.note = note;
        }
    }

    private static final class SimilarTitle {
        private final ThesisApplication application;
        private final double score;

        SimilarTitle(ThesisApplication application, double score) {
            this.application = application;
            this.score = score;
        }
    }

    private static final class SimilarityPair {
        private final ThesisApplication studentTitle;
        private final ThesisApplication comparisonTitle;
        private final double score;

        private SimilarityPair(
                ThesisApplication studentTitle, ThesisApplication comparisonTitle, double score) {
            this.studentTitle = studentTitle;
            this.comparisonTitle = comparisonTitle;
            this.score = score;
        }
    }

    private static final class ThesisUi {
        private static final Path THESIS_DATA_FILE = Path.of("data-pendaftaran-skripsi.dat");
        private static final int THESIS_FILE_MAGIC_V1 = 0x534B5231;
        private static final int THESIS_FILE_MAGIC_V2 = 0x534B5232;
        private static final int THESIS_FILE_MAGIC_V3 = 0x534B5233;
        private static final int THESIS_FILE_MAGIC = 0x534B5234;
        private static final String PENDING_STATUS = "Menunggu Verifikasi";
        private static final DateTimeFormatter DATE_FORMAT =
                DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm", Locale.forLanguageTag("id-ID"));

        private final JFrame frame = new JFrame("Sistem Informasi Pendaftaran Skripsi UNHASY");
        private final String role;
        private final String loggedInNim;
        private final String loggedInAdminUsername;
        private final List<ThesisApplication> applications;
        private boolean externalReloadErrorShown;
        private Timer applicationsRefreshTimer;
        private Database.StudentProfile studentProfile;
        private final JPanel pages = new JPanel(new CardLayout());
        private final JPanel sidebarMenus = new JPanel();
        private final Map<String, SidebarButton> sidebarButtons = new LinkedHashMap<>();
        private String activePage;
        private final DefaultTableModel recentModel = readOnlyTableModel(
                "Nama", "NIM", "Judul Skripsi", "Status");
        private final DefaultTableModel studentModel = readOnlyTableModel(
                "Judul Skripsi", "Bidang Penelitian", "Tanggal Pengajuan", "Status", "Catatan Admin");
        private final DefaultTableModel studentDashboardRecentModel = readOnlyTableModel(
                "No", "Judul Skripsi", "Tanggal Pengajuan", "Status");
        private final DefaultTableModel adminModel = readOnlyTableModel(
                "NIM", "Nama Mahasiswa", "Judul Skripsi", "Tanggal Pengajuan", "Status");
        private final DefaultTableModel adminDashboardModel = new DefaultTableModel(
                new Object[]{"No", "Nama Mahasiswa", "NIM", "Judul Skripsi",
                    "Tanggal Pengajuan", "Status", "Aksi"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return column == 6;
            }
        };
        private final JTable adminDashboardTable = createStyledTable(adminDashboardModel);
        private final JTextField adminDashboardSearch = createStyledTextField("Cari nama mahasiswa atau NIM...");
        private final JComboBox<String> adminDashboardStatus = new JComboBox<>(
                new String[]{"Semua Status", PENDING_STATUS, "Disetujui", "Ditolak"});
        private final JLabel adminDashboardResultCount = new JLabel();
        private final JLabel adminDashboardEmptyTitle = new JLabel("Belum ada pengajuan");
        private final JLabel adminDashboardEmptyDescription = new JLabel();
        private final JPanel adminDashboardResults = new JPanel(new CardLayout());
        private final List<ThesisApplication> adminDashboardRows = new ArrayList<>();
        private final JPanel studentStatusViews = new JPanel(new CardLayout());
        private final JPanel studentStatusCards = new StatusCardsPanel();
        private final JLabel studentStatusCount = new JLabel();
        private final List<ThesisApplication> studentDashboardRecentRows = new ArrayList<>();
        private final List<ThesisApplication> adminRows = new ArrayList<>();
        private final JTextField namaInput = createStyledTextField("Nama lengkap");
        private final JTextField nimInput = createStyledTextField("NIM aktif");
        private final JComboBox<String> prodiInput = createChoice(
                "Pilih program studi",
                "Sistem Informasi",
                "Teknik Informatika",
                "Pendidikan Agama Islam",
                "Pendidikan Guru Sekolah Dasar",
                "Ekonomi Syariah",
                "Hukum Keluarga Islam",
                "Manajemen Pendidikan Islam"
        );
        private final JTextField emailInput = createStyledTextField("nama@email.com");
        private final JComboBox<String> semesterInput = createChoice(
                "Pilih semester",
                "Semester 6", "Semester 7", "Semester 8", "Semester 9", "Semester 10",
                "Semester 11", "Semester 12"
        );
        private final JTextField profileNameInput = createStyledTextField("Nama lengkap");
        private final JTextField profileNimInput = createStyledTextField("NIM");
        private final JTextField profileProgramInput = createStyledTextField("Program studi");
        private final JTextField profileEmailInput = createStyledTextField("nama@email.com");
        private final JComboBox<String> profileSemesterInput = createChoice(
                "Pilih semester",
                "Semester 1", "Semester 2", "Semester 3", "Semester 4", "Semester 5",
                "Semester 6", "Semester 7", "Semester 8", "Semester 9", "Semester 10",
                "Semester 11", "Semester 12", "Semester 13", "Semester 14"
        );
        private final JTextField judulInput = createStyledTextField("Masukkan judul skripsi");
        private final JTextArea deskripsiInput = createStyledTextArea();
        private final JComboBox<String> bidangInput = createChoice(
                "Pilih bidang penelitian",
                "Sistem Informasi",
                "Rekayasa Perangkat Lunak",
                "Kecerdasan Buatan dan Data",
                "Jaringan dan Keamanan",
                "Pendidikan",
                "Ekonomi dan Bisnis",
                "Hukum",
                "Lainnya"
        );
        private final JTextField studentSearch = createStyledTextField("Masukkan NIM Anda");
        private final JTextField adminSearch = createStyledTextField("Cari berdasarkan NIM atau nama mahasiswa");
        private final JComboBox<String> adminStatusFilter = new JComboBox<>(
                new String[]{"Semua Status", PENDING_STATUS, "Disetujui", "Ditolak"});
        private final JPanel adminVerifyActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 9, 0));
        private final JPanel adminManageActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 9, 0));
        private final JPanel adminFilterRow = new JPanel(new BorderLayout(12, 0));
        private final JLabel similarityMatchCount = new JLabel();
        private final JPanel similarityMatchList = new JPanel();
        private final JTable studentTable = createStyledTable(studentModel);
        private final JTable adminTable = createStyledTable(adminModel);
        private final JLabel pendingMetric = new JLabel();
        private final JLabel approvedMetric = new JLabel();
        private final JLabel rejectedMetric = new JLabel();
        private final JLabel totalMetric = new JLabel();
        private final JLabel pendingSummary = new JLabel();
        private final JPanel homeMetrics = new JPanel(new GridLayout(1, 3, 12, 0));
        private final JPanel studentMetricCards = new JPanel(new GridLayout(1, 4, 12, 0));
        private final JPanel studentLatestCardHolder = new JPanel(new BorderLayout());
        private final JTable studentDashboardRecentTable = createStyledTable(studentDashboardRecentModel);
        private final JPanel adminMetricCards = new JPanel(new GridLayout(1, 4, 14, 0));
        private final JLabel studentWelcome = sectionTitle("Selamat datang, Mahasiswa");
        private final JLabel adminPageTitle = sectionTitle("Data Pengajuan Skripsi");
        private final JLabel profileCompletionHint = new JLabel();
        private JLabel topBarStudentName;
        private JPanel homePage;

        ThesisUi(String role, String loggedInNim, String loggedInAdminUsername)
                throws IOException, java.sql.SQLException {
            this.role = role;
            this.loggedInNim = loggedInNim;
            this.loggedInAdminUsername = loggedInAdminUsername;
            applications = loadApplications();
            studentProfile = "Mahasiswa".equals(role)
                    ? Database.findStudentProfile(loggedInNim) : null;
            if ("Mahasiswa".equals(role) && studentProfile == null) {
                throw new java.sql.SQLException("Profil akun mahasiswa tidak ditemukan.");
            }
            buildWindow();
        }

        void show() {
            frame.setVisible(true);
            applicationsRefreshTimer = new Timer(2500, event -> refreshApplicationsFromDatabase());
            applicationsRefreshTimer.start();
            if ("Mahasiswa".equals(role) && !isStudentProfileComplete()) {
                showPage("studentProfile");
                JOptionPane.showMessageDialog(
                        frame,
                        "Lengkapi nama, program studi, semester, dan email pada Profil Saya "
                                + "sebelum mengajukan judul skripsi.",
                        "Profil Belum Lengkap",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        }

        private void buildWindow() {
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            if ("Admin".equals(role)) {
                java.awt.Rectangle usableBounds = java.awt.GraphicsEnvironment
                        .getLocalGraphicsEnvironment().getMaximumWindowBounds();
                int width = Math.min(1360, usableBounds.width - 24);
                int height = Math.min(860, usableBounds.height - 24);
                frame.setMinimumSize(new Dimension(Math.min(980, width), Math.min(700, height)));
                frame.setSize(width, height);
            } else {
                frame.setMinimumSize(new java.awt.Dimension(980, 720));
                frame.setSize(1360, 860);
            }
            pages.setOpaque(false);
            ImageIcon logo = loadLogo();
            if (logo != null) {
                frame.setIconImage(logo.getImage());
            }

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(PAGE_BG);
            root.add(createSidebar(logo), BorderLayout.WEST);
            if ("Mahasiswa".equals(role)) {
                pages.add(createStudentDashboardPage(), "studentDashboard");
                pages.add(createStudentProfilePage(), "studentProfile");
                pages.add(createStudentRegistrationPage(), "studentRegistration");
                pages.add(createStudentStatusPage(), "studentStatus");
                populateProfileFields();
                populateRegistrationProfileFields();
                profileNimInput.setEditable(false);
                nimInput.setEditable(false);
                namaInput.setEditable(false);
                emailInput.setEditable(false);
                prodiInput.setEnabled(false);
                semesterInput.setEnabled(false);
                studentSearch.setText(loggedInNim);
            } else {
                pages.add(createAdminDashboardPage(), "adminDashboard");
                pages.add(createAdminPage(), "adminWorkspace");
                pages.add(createAdminSimilarityPage(), "adminSimilarity");
            }
            JPanel workspace = new DashboardBackdrop(loadBackgroundImage());
            workspace.setLayout(new BorderLayout());
            workspace.add(createTopBar(), BorderLayout.NORTH);
            JPanel pageArea = new JPanel(new BorderLayout());
            pageArea.setOpaque(false);
            pageArea.setBorder(BorderFactory.createEmptyBorder(22, 26, 24, 26));
            pageArea.add(pages, BorderLayout.CENTER);
            workspace.add(pageArea, BorderLayout.CENTER);
            root.add(workspace, BorderLayout.CENTER);
            frame.setContentPane(root);

            refreshAll();
            showPage("Mahasiswa".equals(role) ? "studentDashboard" : "adminDashboard");
            if ("Admin".equals(role)) {
                java.awt.Rectangle usableBounds = java.awt.GraphicsEnvironment
                        .getLocalGraphicsEnvironment().getMaximumWindowBounds();
                frame.setLocation(
                        usableBounds.x + (usableBounds.width - frame.getWidth()) / 2,
                        usableBounds.y + (usableBounds.height - frame.getHeight()) / 2);
            } else {
                frame.setLocationRelativeTo(null);
            }
        }

        private JPanel createSidebar(ImageIcon logo) {
            JPanel sidebar = new JPanel(new BorderLayout(0, 18));
            sidebar.setBackground(DEEP_NAVY);
            sidebar.setPreferredSize(new Dimension("Mahasiswa".equals(role) ? 244 : 260, 700));
            sidebar.setBorder(BorderFactory.createEmptyBorder(24, 16, 20, 16));

            JPanel brand = new JPanel();
            brand.setOpaque(false);
            brand.setLayout(new BoxLayout(brand, BoxLayout.Y_AXIS));
            if (logo != null) {
                JLabel logoLabel = new JLabel(new ImageIcon(
                        logo.getImage().getScaledInstance(78, 78, Image.SCALE_SMOOTH)));
                logoLabel.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
                brand.add(logoLabel);
            }
            JLabel university = new JLabel("<html><div style='text-align:center'>UNIVERSITAS<br>"
                    + "HASYIM ASY'ARI</div></html>");
            university.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            university.setHorizontalAlignment(SwingConstants.CENTER);
            university.setFont(new Font("SansSerif", Font.BOLD, 12));
            university.setForeground(Color.WHITE);
            brand.add(Box.createVerticalStrut(8));
            brand.add(university);
            brand.add(Box.createVerticalStrut(24));
            JLabel section = new JLabel("MENU UTAMA");
            section.setFont(new Font("SansSerif", Font.BOLD, 10));
            section.setForeground(new Color(154, 176, 199));
            section.setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 0));
            section.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            brand.add(section);
            sidebar.add(brand, BorderLayout.NORTH);

            sidebarMenus.setOpaque(false);
            sidebarMenus.setLayout(new BoxLayout(sidebarMenus, BoxLayout.Y_AXIS));
            sidebarButtons.clear();
            if ("Mahasiswa".equals(role)) {
                addSidebarItem("studentDashboard", "home", "Dashboard");
                addSidebarItem("studentProfile", "edit", "Profil Saya");
                addSidebarItem("studentRegistration", "edit", "Pendaftaran Skripsi");
                addSidebarItem("studentStatus", "list", "Cek Status Pengajuan");
            } else {
                addSidebarItem("adminDashboard", "home", "Dashboard");
                addSidebarItem("adminWorkspace", "list", "Data Pengajuan Skripsi");
                addSidebarItem("adminVerify", "check", "Verifikasi Pengajuan");
                addSidebarItem("adminSimilarity", "search", "Pemeriksaan Kemiripan Judul");
            }
            sidebar.add(sidebarMenus, BorderLayout.CENTER);

            JPanel bottom = new JPanel();
            bottom.setOpaque(false);
            bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
            JSeparator separator = new JSeparator();
            separator.setForeground(new Color(58, 79, 104));
            separator.setBackground(new Color(58, 79, 104));
            bottom.add(separator);
            SidebarButton logout = new SidebarButton("logout", "Logout");
            logout.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            logout.addActionListener(e -> logout());
            bottom.add(Box.createVerticalStrut(12));
            bottom.add(logout);
            bottom.add(Box.createVerticalStrut(10));
            JLabel motto = new JLabel("<html><div style='text-align:center'>UNGGUL • RELIGIUS<br>"
                    + "BERDAYA SAING</div></html>");
            motto.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            motto.setHorizontalAlignment(SwingConstants.CENTER);
            motto.setFont(new Font("SansSerif", Font.PLAIN, 9));
            motto.setForeground(new Color(154, 176, 199));
            bottom.add(motto);
            sidebar.add(bottom, BorderLayout.SOUTH);
            return sidebar;
        }

        private void addSidebarItem(String key, String icon, String title) {
            SidebarButton button = new SidebarButton(icon, title);
            button.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            button.addActionListener(e -> {
                if ("adminVerify".equals(key)) {
                    openAdminWorkspace("Verifikasi Pengajuan");
                    setActiveSidebarItem(key);
                } else if ("adminWorkspace".equals(key)) {
                    openAdminWorkspace("Data Pengajuan Skripsi");
                } else {
                    showPage(key);
                }
            });
            sidebarButtons.put(key, button);
            sidebarMenus.add(button);
            sidebarMenus.add(Box.createVerticalStrut(5));
        }

        private JPanel createTopBar() {
            JPanel topBar = new JPanel(new BorderLayout());
            topBar.setBackground(Color.WHITE);
            topBar.setBorder(BorderFactory.createEmptyBorder(12, 28, 12, 30));
            topBar.setPreferredSize(new Dimension(900, 72));

            JPanel breadcrumb = new JPanel();
            breadcrumb.setOpaque(false);
            breadcrumb.setLayout(new BoxLayout(breadcrumb, BoxLayout.Y_AXIS));
            JLabel context = new JLabel("PORTAL AKADEMIK  /  " + role.toUpperCase(Locale.ROOT));
            context.setFont(new Font("SansSerif", Font.BOLD, 10));
            context.setForeground(TEAL);
            JLabel caption = new JLabel("Sistem Informasi Pendaftaran Skripsi");
            caption.setFont(new Font("SansSerif", Font.BOLD, 16));
            caption.setForeground(NAVY);
            breadcrumb.add(context);
            breadcrumb.add(Box.createVerticalStrut(4));
            breadcrumb.add(caption);
            topBar.add(breadcrumb, BorderLayout.WEST);

            JPanel account = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 2));
            account.setOpaque(false);
            JLabel avatar = new JLabel(" " + ("Mahasiswa".equals(role) ? "M" : "A") + " ");
            avatar.setFont(new Font("SansSerif", Font.BOLD, 14));
            avatar.setForeground(Color.WHITE);
            avatar.setOpaque(true);
            avatar.setBackground(TEAL);
            avatar.setBorder(BorderFactory.createEmptyBorder(5, 3, 5, 3));
            JPanel identity = new JPanel();
            identity.setOpaque(false);
            identity.setLayout(new BoxLayout(identity, BoxLayout.Y_AXIS));
            JLabel name = new JLabel("Mahasiswa".equals(role) ? studentDisplayName() : "Administrator");
            name.setFont(new Font("SansSerif", Font.BOLD, 12));
            name.setForeground(NAVY);
            if ("Mahasiswa".equals(role)) {
                topBarStudentName = name;
            }
            JLabel userRole = bodyText(role);
            identity.add(name);
            identity.add(Box.createVerticalStrut(2));
            identity.add(userRole);
            account.add(avatar);
            account.add(identity);
            topBar.add(account, BorderLayout.EAST);
            return topBar;
        }

        private JPanel createHeader(ImageIcon logo) {
            GradientPanel header = new GradientPanel();
            header.setLayout(new BorderLayout(18, 0));
            header.setBorder(BorderFactory.createEmptyBorder(14, 24, 14, 26));
            header.setPreferredSize(new java.awt.Dimension(900, 116));
            if (logo != null) {
                JLabel logoLabel = new JLabel(new ImageIcon(
                        logo.getImage().getScaledInstance(82, 82, Image.SCALE_SMOOTH)));
                header.add(logoLabel, BorderLayout.WEST);
            }

            JPanel titleBlock = new JPanel();
            titleBlock.setOpaque(false);
            titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
            JLabel campus = new JLabel("UNIVERSITAS HASYIM ASY'ARI");
            campus.setFont(new Font("SansSerif", Font.BOLD, 13));
            campus.setForeground(new Color(215, 235, 239));
            JLabel title = new JLabel("Pendaftaran Skripsi");
            title.setFont(new Font("SansSerif", Font.BOLD, 30));
            title.setForeground(Color.WHITE);
            JLabel subtitle = new JLabel("Sistem informasi pengajuan dan persetujuan judul skripsi");
            subtitle.setFont(new Font("SansSerif", Font.PLAIN, 14));
            subtitle.setForeground(new Color(229, 240, 244));
            titleBlock.add(campus);
            titleBlock.add(Box.createVerticalStrut(3));
            titleBlock.add(title);
            titleBlock.add(Box.createVerticalStrut(2));
            titleBlock.add(subtitle);
            header.add(titleBlock, BorderLayout.CENTER);

            JLabel term = new JLabel("LAYANAN AKADEMIK  •  SKRIPSI");
            term.setFont(new Font("SansSerif", Font.BOLD, 11));
            term.setForeground(Color.WHITE);
            header.add(term, BorderLayout.EAST);
            return header;
        }

        private JPanel createNavigation() {
            JPanel navigation = new JPanel(new BorderLayout());
            navigation.setOpaque(false);
            JPanel links = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
            links.setOpaque(false);
            if ("Mahasiswa".equals(role)) {
                JButton dashboard = navButton("Dashboard");
                JButton registration = navButton("Pendaftaran Skripsi");
                JButton status = navButton("Cek Status Pengajuan");
                dashboard.addActionListener(e -> showPage("studentDashboard"));
                registration.addActionListener(e -> showPage("studentRegistration"));
                status.addActionListener(e -> showPage("studentStatus"));
                links.add(dashboard);
                links.add(registration);
                links.add(status);
            } else {
                JButton dashboard = navButton("Dashboard");
                JButton data = navButton("Data Pengajuan Skripsi");
                JButton verification = navButton("Verifikasi Pengajuan");
                JButton similarity = navButton("Pemeriksaan Kemiripan Judul");
                dashboard.addActionListener(e -> showPage("adminDashboard"));
                data.addActionListener(e -> openAdminWorkspace("Data Pengajuan Skripsi"));
                verification.addActionListener(e -> openAdminWorkspace("Verifikasi Pengajuan"));
                similarity.addActionListener(e -> showPage("adminSimilarity"));
                links.add(dashboard);
                links.add(data);
                links.add(verification);
                links.add(similarity);
            }
            navigation.add(links, BorderLayout.WEST);

            JPanel account = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
            account.setOpaque(false);
            JLabel currentRole = new JLabel("●  " + role);
            currentRole.setFont(new Font("SansSerif", Font.BOLD, 12));
            currentRole.setForeground(NAVY);
            JButton logout = navButton("Logout");
            logout.setBackground(new Color(101, 114, 132));
            logout.addActionListener(e -> logout());
            account.add(currentRole);
            account.add(logout);
            navigation.add(account, BorderLayout.EAST);
            return navigation;
        }

        private void openAdminWorkspace(String title) {
            boolean verificationPage = "Verifikasi Pengajuan".equals(title);
            adminPageTitle.setText(verificationPage ? "Verifikasi Pengajuan Skripsi" : title);
            adminStatusFilter.setVisible(verificationPage);
            adminVerifyActions.setVisible(verificationPage);
            adminManageActions.setVisible(!verificationPage);
            showPage("adminWorkspace");
            setActiveSidebarItem(verificationPage ? "adminVerify" : "adminWorkspace");
        }

        private void logout() {
            if (applicationsRefreshTimer != null) {
                applicationsRefreshTimer.stop();
            }
            frame.dispose();
            new LoginUi().show();
        }

        private JButton navButton(String text) {
            JButton button = new RoundedButton(text, NAVY, Color.WHITE);
            button.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
            button.setFont(new Font("SansSerif", Font.BOLD, 13));
            return button;
        }

        private JPanel createHomePage() {
            homePage = new GlassPanel();
            homePage.setLayout(new BorderLayout(0, 16));
            homePage.setBorder(BorderFactory.createEmptyBorder(22, 24, 22, 24));

            JPanel intro = new JPanel(new BorderLayout());
            intro.setOpaque(false);
            JPanel heading = new JPanel();
            heading.setOpaque(false);
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            JLabel title = sectionTitle("Ringkasan Pendaftaran Skripsi");
            JLabel description = bodyText("Pantau proses pengajuan judul dan kelola persetujuan dalam satu tempat.");
            heading.add(title);
            heading.add(Box.createVerticalStrut(5));
            heading.add(description);
            intro.add(heading, BorderLayout.WEST);
            totalMetric.setFont(new Font("SansSerif", Font.BOLD, 14));
            totalMetric.setForeground(TEAL);
            intro.add(totalMetric, BorderLayout.EAST);
            homePage.add(intro, BorderLayout.NORTH);

            JPanel center = new JPanel(new BorderLayout(0, 16));
            center.setOpaque(false);
            homeMetrics.setOpaque(false);
            center.add(homeMetrics, BorderLayout.NORTH);

            JPanel lower = new JPanel(new GridLayout(1, 2, 14, 0));
            lower.setOpaque(false);
            lower.add(createRecentPanel());
            lower.add(createQuickActionsPanel());
            center.add(lower, BorderLayout.CENTER);
            homePage.add(center, BorderLayout.CENTER);
            return homePage;
        }

        private JPanel createRecentPanel() {
            GlassPanel panel = sectionCard();
            panel.setLayout(new BorderLayout(0, 10));
            panel.add(sectionTitle("Pengajuan Terbaru"), BorderLayout.NORTH);
            JTable recent = createStyledTable(recentModel);
            panel.add(tableScroll(recent), BorderLayout.CENTER);
            return panel;
        }

        private JPanel createQuickActionsPanel() {
            GlassPanel panel = sectionCard();
            panel.setLayout(new BorderLayout(0, 14));
            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            JLabel title = sectionTitle("Mulai dari sini");
            JLabel explainer = bodyText("Mahasiswa dapat mendaftarkan judul dan memeriksa status. Admin dapat memeriksa kemiripan serta mengelola keputusan.");
            explainer.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            JButton student = new RoundedButton("Ajukan Judul Skripsi", TEAL, Color.WHITE);
            student.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            student.addActionListener(e -> showPage("student"));
            JButton admin = new RoundedButton("Buka Panel Admin", NAVY, Color.WHITE);
            admin.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            admin.addActionListener(e -> {
                if (verifyAdmin()) {
                    showPage("admin");
                }
            });
            pendingSummary.setFont(new Font("SansSerif", Font.BOLD, 14));
            pendingSummary.setForeground(TEAL);
            content.add(title);
            content.add(Box.createVerticalStrut(10));
            content.add(explainer);
            content.add(Box.createVerticalStrut(22));
            content.add(student);
            content.add(Box.createVerticalStrut(10));
            content.add(admin);
            content.add(Box.createVerticalStrut(18));
            content.add(pendingSummary);
            panel.add(content, BorderLayout.CENTER);
            return panel;
        }

        private JPanel createStudentPage() {
            JPanel page = new JPanel(new BorderLayout(0, 12));
            page.setOpaque(false);
            page.add(createPageHeading("Area Mahasiswa",
                    "Lengkapi data diri, ajukan judul, dan lacak status menggunakan NIM."), BorderLayout.NORTH);

            JPanel columns = new JPanel(new GridLayout(1, 2, 14, 0));
            columns.setOpaque(false);
            columns.add(createStudentForm());
            columns.add(createStudentTracker());
            page.add(columns, BorderLayout.CENTER);
            return page;
        }

        private JPanel createStudentDashboardPage() {
            JPanel page = new JPanel(new BorderLayout());
            page.setOpaque(false);
            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

            JPanel hero = createStudentDashboardHeader();
            hero.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            hero.setMaximumSize(new Dimension(Integer.MAX_VALUE, 142));
            content.add(hero);
            content.add(Box.createVerticalStrut(13));

            studentMetricCards.setOpaque(false);
            studentMetricCards.setPreferredSize(new Dimension(0, 96));
            studentMetricCards.setMaximumSize(new Dimension(Integer.MAX_VALUE, 96));
            studentMetricCards.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            content.add(studentMetricCards);
            content.add(Box.createVerticalStrut(13));

            JPanel recentCard = createStudentDashboardRecentPanel();
            recentCard.setPreferredSize(new Dimension(0, 225));
            recentCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 225));
            recentCard.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            content.add(recentCard);
            content.add(Box.createVerticalStrut(13));

            studentLatestCardHolder.setOpaque(false);
            studentLatestCardHolder.setPreferredSize(new Dimension(0, 190));
            studentLatestCardHolder.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
            studentLatestCardHolder.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            JPanel lower = new JPanel(new GridLayout(1, 2, 13, 0));
            lower.setOpaque(false);
            lower.setPreferredSize(new Dimension(0, 190));
            lower.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));
            lower.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            lower.add(studentLatestCardHolder);
            lower.add(createStudentQuickActionsPanel());
            content.add(lower);

            JScrollPane dashboardScroll = new JScrollPane(
                    content,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            dashboardScroll.setBorder(BorderFactory.createEmptyBorder());
            dashboardScroll.setOpaque(false);
            dashboardScroll.getViewport().setOpaque(false);
            dashboardScroll.getVerticalScrollBar().setUnitIncrement(16);
            dashboardScroll.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
            page.add(dashboardScroll, BorderLayout.CENTER);

            refreshStudentDashboardLatestCard();
            refreshStudentDashboardRows();
            return page;
        }

        private JPanel createStudentDashboardHeader() {
            JPanel header = new StudentHeroPanel(loadBackgroundImage());
            header.setLayout(new BorderLayout());
            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            studentWelcome.setText("Selamat Datang, " + studentDisplayName() + "!");
            studentWelcome.setFont(new Font("SansSerif", Font.BOLD, 29));
            studentWelcome.setForeground(Color.WHITE);
            JLabel campus = new JLabel("UNIVERSITAS HASYIM ASY'ARI");
            campus.setFont(new Font("SansSerif", Font.BOLD, 10));
            campus.setForeground(new Color(174, 231, 236));
            JLabel subtitle = new JLabel("Sistem Informasi Pendaftaran Skripsi UNHASY");
            subtitle.setFont(new Font("SansSerif", Font.BOLD, 14));
            subtitle.setForeground(new Color(240, 248, 255));
            JLabel nim = new JLabel("NIM  •  " + loggedInNim);
            nim.setFont(new Font("SansSerif", Font.PLAIN, 11));
            nim.setForeground(new Color(220, 236, 247));
            copy.add(campus);
            copy.add(Box.createVerticalStrut(5));
            copy.add(studentWelcome);
            copy.add(Box.createVerticalStrut(4));
            copy.add(subtitle);
            copy.add(Box.createVerticalStrut(4));
            copy.add(nim);
            header.add(copy, BorderLayout.CENTER);
            return header;
        }

        private JPanel createStudentLatestApplicationCard() {
            GlassPanel card = sectionCard();
            card.setLayout(new BorderLayout(0, 12));
            card.setBorder(BorderFactory.createEmptyBorder(15, 17, 14, 17));
            JPanel heading = new JPanel(new BorderLayout(8, 0));
            heading.setOpaque(false);
            heading.add(new MetricGlyph("◷", TEAL, 30), BorderLayout.WEST);
            JLabel title = sectionTitle("Status Pengajuan Anda");
            title.setFont(new Font("SansSerif", Font.BOLD, 16));
            heading.add(title, BorderLayout.CENTER);
            card.add(heading, BorderLayout.NORTH);

            ThesisApplication latest = latestStudentApplication();
            if (latest == null) {
                JPanel empty = new JPanel();
                empty.setOpaque(false);
                empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
                JLabel emptyTitle = new JLabel("Belum ada pengajuan skripsi");
                emptyTitle.setFont(new Font("SansSerif", Font.BOLD, 14));
                emptyTitle.setForeground(NAVY);
                JLabel description = bodyText("Ajukan judul skripsi untuk melihat perkembangan prosesnya.");
                JButton register = new RoundedButton("Daftar Skripsi  →", TEAL, Color.WHITE);
                register.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
                register.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
                register.addActionListener(e -> showPage("studentRegistration"));
                empty.add(Box.createVerticalStrut(4));
                empty.add(emptyTitle);
                empty.add(Box.createVerticalStrut(4));
                empty.add(description);
                empty.add(Box.createVerticalStrut(10));
                empty.add(register);
                card.add(empty, BorderLayout.CENTER);
            } else {
                JPanel details = new JPanel();
                details.setOpaque(false);
                details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
                JPanel status = new JPanel(new BorderLayout(8, 0));
                status.setOpaque(false);
                status.add(createStatusBadge(latest.status), BorderLayout.WEST);
                JLabel update = bodyText("Diajukan " + displayDate(latest.tanggal));
                update.setFont(new Font("SansSerif", Font.PLAIN, 10));
                status.add(update, BorderLayout.CENTER);
                details.add(status);
                details.add(Box.createVerticalStrut(8));
                JLabel thesisTitle = new JLabel(statusCardTextHtml(latest.judul, 300));
                thesisTitle.setFont(new Font("SansSerif", Font.BOLD, 12));
                thesisTitle.setForeground(NAVY);
                details.add(thesisTitle);
                details.add(Box.createVerticalStrut(5));
                String statusDescription = switch (latest.status) {
                    case "Disetujui" -> "Judul skripsi Anda telah disetujui Admin.";
                    case "Ditolak" ->
                            "Pengajuan ditolak. Lihat status untuk membaca catatan Admin.";
                    default -> "Pengajuan Anda sedang diverifikasi oleh Admin.";
                };
                JLabel description = new JLabel(statusCardTextHtml(statusDescription, 340));
                description.setFont(new Font("SansSerif", Font.PLAIN, 11));
                description.setForeground(MUTED);
                details.add(description);
                details.add(Box.createVerticalGlue());
                JButton view = new RoundedButton("Cek Status Pengajuan  →", NAVY, Color.WHITE);
                view.setFont(new Font("SansSerif", Font.BOLD, 11));
                view.setBorder(BorderFactory.createEmptyBorder(7, 11, 7, 11));
                view.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
                view.addActionListener(event -> showPage("studentStatus"));
                details.add(Box.createVerticalStrut(6));
                details.add(view);
                card.add(details, BorderLayout.CENTER);
            }
            return card;
        }

        private JPanel createStudentDashboardRecentPanel() {
            GlassPanel card = sectionCard();
            card.setLayout(new BorderLayout(0, 12));
            card.setBorder(BorderFactory.createEmptyBorder(15, 17, 14, 17));

            JPanel heading = new JPanel(new BorderLayout(10, 0));
            heading.setOpaque(false);
            JPanel title = new JPanel(new BorderLayout(9, 0));
            title.setOpaque(false);
            title.add(new MetricGlyph("▤", TEAL, 30), BorderLayout.WEST);
            JLabel label = sectionTitle("Pengajuan Terbaru");
            label.setFont(new Font("SansSerif", Font.BOLD, 17));
            title.add(label, BorderLayout.CENTER);
            heading.add(title, BorderLayout.WEST);
            JButton viewAll = new RoundedButton("Lihat Semua  →", TEAL, Color.WHITE);
            viewAll.setFont(new Font("SansSerif", Font.BOLD, 11));
            viewAll.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
            viewAll.addActionListener(event -> showPage("studentStatus"));
            heading.add(viewAll, BorderLayout.EAST);
            card.add(heading, BorderLayout.NORTH);

            studentDashboardRecentTable.setRowHeight(37);
            studentDashboardRecentTable.setFillsViewportHeight(true);
            studentDashboardRecentTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            studentDashboardRecentTable.getTableHeader().setPreferredSize(new Dimension(0, 35));
            studentDashboardRecentTable.getColumnModel().getColumn(0).setPreferredWidth(42);
            studentDashboardRecentTable.getColumnModel().getColumn(1).setPreferredWidth(360);
            studentDashboardRecentTable.getColumnModel().getColumn(2).setPreferredWidth(145);
            studentDashboardRecentTable.getColumnModel().getColumn(3).setPreferredWidth(150);
            studentDashboardRecentTable.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        showSelectedStudentDashboardDetails();
                    }
                }
            });
            JScrollPane tableScroll = tableScroll(studentDashboardRecentTable);
            tableScroll.setBorder(BorderFactory.createLineBorder(new Color(219, 231, 241)));
            card.add(tableScroll, BorderLayout.CENTER);
            return card;
        }

        private JPanel createStudentQuickActionsPanel() {
            GlassPanel card = sectionCard();
            card.setLayout(new BorderLayout(0, 12));
            card.setBorder(BorderFactory.createEmptyBorder(15, 17, 14, 17));
            JPanel heading = new JPanel(new BorderLayout(8, 0));
            heading.setOpaque(false);
            heading.add(new MetricGlyph("↗", TEAL, 30), BorderLayout.WEST);
            JLabel title = sectionTitle("Menu Cepat");
            title.setFont(new Font("SansSerif", Font.BOLD, 16));
            heading.add(title, BorderLayout.CENTER);
            card.add(heading, BorderLayout.NORTH);

            JPanel actions = new JPanel(new GridLayout(1, 3, 9, 0));
            actions.setOpaque(false);
            actions.add(createStudentQuickAction(
                    "Pendaftaran<br>Skripsi", "edit", "studentRegistration"));
            actions.add(createStudentQuickAction(
                    "Cek Status<br>Pengajuan", "search", "studentStatus"));
            actions.add(createStudentQuickAction(
                    "Lihat Profil<br>Mahasiswa", "profile", "studentProfile"));
            card.add(actions, BorderLayout.CENTER);
            return card;
        }

        private JButton createStudentQuickAction(String label, String glyph, String page) {
            JButton action = new StudentQuickActionButton(label, glyph);
            action.addActionListener(event -> showPage(page));
            return action;
        }

        private void refreshStudentDashboardRows() {
            studentDashboardRecentModel.setRowCount(0);
            studentDashboardRecentRows.clear();
            List<ThesisApplication> ownApplications = studentApplications();
            for (int i = 0; i < Math.min(5, ownApplications.size()); i++) {
                ThesisApplication application = ownApplications.get(i);
                studentDashboardRecentRows.add(application);
                studentDashboardRecentModel.addRow(new Object[]{
                        i + 1,
                        application.judul,
                        displayDate(application.tanggal),
                        application.status
                });
            }
        }

        private void showSelectedStudentDashboardDetails() {
            int row = studentDashboardRecentTable.getSelectedRow();
            if (row >= 0 && row < studentDashboardRecentRows.size()) {
                showApplicationDetails(studentDashboardRecentRows.get(row));
            }
        }

        private void refreshStudentDashboardLatestCard() {
            if (studentLatestCardHolder.getParent() == null) {
                return;
            }
            studentLatestCardHolder.removeAll();
            studentLatestCardHolder.add(createStudentLatestApplicationCard(), BorderLayout.CENTER);
            studentLatestCardHolder.revalidate();
            studentLatestCardHolder.repaint();
        }

        private JPanel createStudentRegistrationPage() {
            JPanel page = new JPanel(new BorderLayout(0, 12));
            page.setOpaque(false);
            page.add(createPageHeading("Pendaftaran Skripsi",
                    "Isi informasi rencana penelitian untuk mengajukan judul skripsi."), BorderLayout.NORTH);
            page.add(createStudentRegistrationForm(), BorderLayout.CENTER);
            return page;
        }

        private JPanel createStudentProfilePage() {
            JPanel page = new JPanel(new BorderLayout(0, 14));
            page.setOpaque(false);
            page.add(createPageHeading("Profil Mahasiswa",
                    "Perbarui informasi profil yang terhubung dengan akun NIM Anda."),
                    BorderLayout.NORTH);

            GlassPanel card = sectionCard();
            card.setLayout(new BorderLayout(0, 16));
            card.setBorder(BorderFactory.createEmptyBorder(22, 24, 20, 24));

            JPanel heading = new JPanel();
            heading.setOpaque(false);
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            JLabel title = sectionTitle("Informasi Akun");
            title.setFont(new Font("SansSerif", Font.BOLD, 19));
            JLabel description = bodyText("Data ini akan otomatis digunakan pada formulir pengajuan skripsi.");
            heading.add(title);
            heading.add(Box.createVerticalStrut(5));
            heading.add(description);
            card.add(heading, BorderLayout.NORTH);

            JPanel fields = new JPanel(new GridLayout(0, 2, 14, 12));
            fields.setOpaque(false);
            fields.add(createRegistrationField("Nama Lengkap", profileNameInput));
            fields.add(createRegistrationField("NIM (tetap)", profileNimInput));
            fields.add(createRegistrationField("Program Studi", profileProgramInput));
            fields.add(createRegistrationField("Semester", profileSemesterInput));
            fields.add(createRegistrationField("Email", profileEmailInput));
            JPanel fieldsWrap = new JPanel(new BorderLayout());
            fieldsWrap.setOpaque(false);
            fieldsWrap.add(fields, BorderLayout.NORTH);
            card.add(fieldsWrap, BorderLayout.CENTER);

            JPanel footer = new JPanel(new BorderLayout(12, 0));
            footer.setOpaque(false);
            profileCompletionHint.setFont(new Font("SansSerif", Font.PLAIN, 12));
            profileCompletionHint.setForeground(MUTED);
            footer.add(profileCompletionHint, BorderLayout.CENTER);
            JButton save = new RoundedButton("Simpan Profil", TEAL, Color.WHITE);
            save.setBorder(BorderFactory.createEmptyBorder(11, 18, 11, 18));
            save.addActionListener(event -> saveStudentProfile());
            footer.add(save, BorderLayout.EAST);
            card.add(footer, BorderLayout.SOUTH);
            page.add(card, BorderLayout.CENTER);
            updateProfileCompletionHint();
            return page;
        }

        private void populateProfileFields() {
            profileNameInput.setText(studentProfile.name());
            profileNimInput.setText(studentProfile.nim());
            profileProgramInput.setText(studentProfile.program());
            profileEmailInput.setText(studentProfile.email());
            String semester = "Semester " + studentProfile.semester();
            ensureComboItem(profileSemesterInput, semester);
            profileSemesterInput.setSelectedItem(semester);
        }

        private void populateRegistrationProfileFields() {
            namaInput.setText(studentProfile.name());
            nimInput.setText(studentProfile.nim());
            emailInput.setText(studentProfile.email());
            String semester = "Semester " + studentProfile.semester();
            ensureComboItem(semesterInput, semester);
            semesterInput.setSelectedItem(semester);
            ensureComboItem(prodiInput, studentProfile.program());
            prodiInput.setSelectedItem(studentProfile.program());
        }

        private void ensureComboItem(JComboBox<String> combo, String value) {
            if (value == null || value.isBlank()) {
                return;
            }
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (value.equals(combo.getItemAt(i))) {
                    return;
                }
            }
            combo.addItem(value);
        }

        private boolean isStudentProfileComplete() {
            return studentProfile != null
                    && studentProfile.isComplete()
                    && studentProfile.email().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
        }

        private void updateProfileCompletionHint() {
            if (isStudentProfileComplete()) {
                profileCompletionHint.setText("Profil lengkap dan siap digunakan untuk pengajuan.");
                profileCompletionHint.setForeground(APPROVED);
            } else {
                profileCompletionHint.setText("Lengkapi nama, program studi, semester, dan email sebelum mengajukan.");
                profileCompletionHint.setForeground(WAITING);
            }
        }

        private void saveStudentProfile() {
            String name = profileNameInput.getText().trim();
            String program = profileProgramInput.getText().trim();
            String email = profileEmailInput.getText().trim();
            Object selectedSemester = profileSemesterInput.getSelectedItem();
            String semesterText = selectedSemester == null ? "" : selectedSemester.toString();
            if (!semesterText.matches("Semester\\s+\\d+")) {
                showWarning("Pilih semester pada profil terlebih dahulu.");
                return;
            }
            int semester = Integer.parseInt(semesterText.replace("Semester", "").trim());
            if (!email.isEmpty() && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                showWarning("Format email belum valid.");
                return;
            }

            try {
                studentProfile = Database.updateStudentProfile(
                        loggedInNim, name, program, semester, email);
                populateProfileFields();
                populateRegistrationProfileFields();
                updateProfileCompletionHint();
                if (topBarStudentName != null) {
                    topBarStudentName.setText(studentDisplayName());
                }
                studentWelcome.setText("Selamat Datang, " + studentDisplayName() + "!");
                refreshDashboard();
                JOptionPane.showMessageDialog(
                        frame,
                        isStudentProfileComplete()
                                ? "Profil berhasil disimpan."
                                : "Profil tersimpan. Lengkapi semua data profil sebelum mengajukan judul.",
                        isStudentProfileComplete() ? "Profil Tersimpan" : "Profil Belum Lengkap",
                        isStudentProfileComplete()
                                ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
            } catch (java.sql.SQLException ex) {
                showError("Profil tidak dapat disimpan: " + ex.getMessage());
            }
        }

        private JPanel createStudentRegistrationForm() {
            GlassPanel card = sectionCard();
            card.setLayout(new BorderLayout(0, 14));
            card.setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));

            JPanel heading = new JPanel();
            heading.setOpaque(false);
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            JLabel title = sectionTitle("Formulir Pengajuan");
            title.setFont(new Font("SansSerif", Font.BOLD, 19));
            JLabel description = bodyText("Lengkapi informasi rencana penelitian Anda.");
            heading.add(title);
            heading.add(Box.createVerticalStrut(4));
            heading.add(description);
            card.add(heading, BorderLayout.NORTH);

            JPanel fields = new JPanel(new GridBagLayout());
            fields.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(0, 0, 10, 12);
            c.weightx = 1;
            c.gridx = 0;
            c.gridy = 0;
            fields.add(createRegistrationField("Nama Lengkap", namaInput), c);
            c.gridx = 1;
            c.insets = new Insets(0, 0, 10, 0);
            fields.add(createRegistrationField("NIM", nimInput), c);

            c.gridy++;
            c.gridx = 0;
            c.insets = new Insets(0, 0, 10, 12);
            fields.add(createRegistrationField("Program Studi", prodiInput), c);
            c.gridx = 1;
            c.insets = new Insets(0, 0, 10, 0);
            fields.add(createRegistrationField("Semester", semesterInput), c);

            c.gridy++;
            c.gridx = 0;
            c.insets = new Insets(0, 0, 10, 12);
            fields.add(createRegistrationField("Judul Skripsi", judulInput), c);
            judulInput.addActionListener(e -> checkStudentTitle());
            c.gridx = 1;
            c.insets = new Insets(0, 0, 10, 0);
            fields.add(createRegistrationField("Bidang Penelitian", bidangInput), c);

            JScrollPane descriptionScroll = new JScrollPane(
                    deskripsiInput,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            descriptionScroll.setPreferredSize(new Dimension(300, 96));
            descriptionScroll.setBorder(BorderFactory.createLineBorder(new Color(192, 210, 232)));
            descriptionScroll.getViewport().setBackground(Color.WHITE);
            descriptionScroll.getVerticalScrollBar().setUnitIncrement(14);
            descriptionScroll.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
            c.gridy++;
            c.gridx = 0;
            c.gridwidth = 2;
            c.weightx = 1;
            c.insets = new Insets(0, 0, 10, 0);
            fields.add(createRegistrationField(
                    "Deskripsi / Latar Belakang Penelitian", descriptionScroll), c);

            c.gridy++;
            c.gridwidth = 1;
            c.gridx = 0;
            c.insets = new Insets(0, 0, 0, 12);
            fields.add(createRegistrationField("Email Profil", emailInput), c);

            JPanel fieldsContainer = new JPanel(new BorderLayout());
            fieldsContainer.setOpaque(false);
            fieldsContainer.add(fields, BorderLayout.NORTH);
            card.add(fieldsContainer, BorderLayout.CENTER);

            JSeparator divider = new JSeparator();
            divider.setForeground(new Color(224, 233, 241));
            divider.setBackground(new Color(224, 233, 241));
            JPanel footer = new JPanel(new BorderLayout(0, 10));
            footer.setOpaque(false);
            footer.add(divider, BorderLayout.NORTH);
            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            buttons.setOpaque(false);
            JButton check = new RoundedButton("Cek Kemiripan", NAVY, Color.WHITE);
            JButton submit = new RoundedButton("Simpan Pengajuan  →", TEAL, Color.WHITE);
            JButton reset = new ResetButton();
            check.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));
            submit.setBorder(BorderFactory.createEmptyBorder(10, 17, 10, 17));
            reset.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
            check.addActionListener(e -> checkStudentTitle());
            submit.addActionListener(e -> submitApplication());
            reset.addActionListener(e -> clearStudentForm());
            buttons.add(check);
            buttons.add(reset);
            buttons.add(submit);
            footer.add(buttons, BorderLayout.SOUTH);
            card.add(footer, BorderLayout.SOUTH);
            return card;
        }

        private JPanel createRegistrationField(String labelText, JComponent input) {
            JPanel field = new JPanel(new BorderLayout(0, 5));
            field.setOpaque(false);
            JLabel label = new JLabel(labelText);
            label.setFont(new Font("SansSerif", Font.BOLD, 12));
            label.setForeground(NAVY);
            field.add(label, BorderLayout.NORTH);

            if (input instanceof JTextField || input instanceof JComboBox<?>) {
                JPanel shell = new RoundedFieldPanel();
                shell.setLayout(new BorderLayout());
                shell.setPreferredSize(new Dimension(280, 40));
                shell.setMinimumSize(new Dimension(140, 40));
                if (input instanceof JComboBox<?>) {
                    input.setPreferredSize(new Dimension(220, 36));
                } else {
                    input.setPreferredSize(new Dimension(240, 36));
                }
                shell.add(input, BorderLayout.CENTER);
                field.add(shell, BorderLayout.CENTER);
            } else {
                field.add(input, BorderLayout.CENTER);
            }
            return field;
        }

        private JPanel createStudentStatusPage() {
            JPanel page = new JPanel(new BorderLayout(0, 16));
            page.setOpaque(false);
            studentSearch.setText(loggedInNim);
            studentSearch.setEditable(false);

            JPanel heading = new JPanel(new BorderLayout(16, 0));
            heading.setOpaque(false);
            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            copy.add(sectionTitle("Cek Status Pengajuan"));
            copy.add(Box.createVerticalStrut(5));
            copy.add(bodyText("Pantau perkembangan dan catatan untuk pengajuan skripsi Anda."));
            heading.add(copy, BorderLayout.WEST);
            JLabel nimBadge = new JLabel("NIM  •  " + loggedInNim);
            nimBadge.setFont(new Font("SansSerif", Font.BOLD, 12));
            nimBadge.setForeground(TEAL);
            nimBadge.setOpaque(true);
            nimBadge.setBackground(new Color(226, 244, 245, 235));
            nimBadge.setBorder(BorderFactory.createEmptyBorder(9, 13, 9, 13));
            heading.add(nimBadge, BorderLayout.EAST);
            page.add(heading, BorderLayout.NORTH);

            GlassPanel card = sectionCard();
            card.setLayout(new BorderLayout(0, 14));
            card.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
            JPanel cardHeading = new JPanel(new BorderLayout(12, 0));
            cardHeading.setOpaque(false);
            JPanel headingTitle = new JPanel(new BorderLayout(10, 0));
            headingTitle.setOpaque(false);
            headingTitle.add(new MetricGlyph("≡", TEAL, 34), BorderLayout.WEST);
            JLabel title = sectionTitle("Riwayat Pengajuan");
            title.setFont(new Font("SansSerif", Font.BOLD, 18));
            headingTitle.add(title, BorderLayout.CENTER);
            cardHeading.add(headingTitle, BorderLayout.WEST);
            studentStatusCount.setFont(new Font("SansSerif", Font.PLAIN, 12));
            studentStatusCount.setForeground(MUTED);
            cardHeading.add(studentStatusCount, BorderLayout.EAST);
            card.add(cardHeading, BorderLayout.NORTH);

            studentStatusCards.setOpaque(false);
            studentStatusCards.setLayout(new BoxLayout(studentStatusCards, BoxLayout.Y_AXIS));
            JScrollPane scroll = new JScrollPane(studentStatusCards,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(16);
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
            studentStatusViews.setOpaque(false);
            studentStatusViews.add(scroll, "entries");
            studentStatusViews.add(createStudentStatusEmptyState(), "empty");
            card.add(studentStatusViews, BorderLayout.CENTER);
            page.add(card, BorderLayout.CENTER);
            refreshStudentStatusCards();
            return page;
        }

        private JPanel createStudentStatusEmptyState() {
            JPanel empty = new JPanel(new GridBagLayout());
            empty.setOpaque(false);
            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            EmptyStateIcon icon = new EmptyStateIcon();
            icon.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            JLabel title = new JLabel("Belum ada pengajuan skripsi");
            title.setFont(new Font("SansSerif", Font.BOLD, 18));
            title.setForeground(NAVY);
            title.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            JLabel description = bodyText("Pengajuan yang Anda buat akan tampil di halaman ini.");
            description.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            JButton register = new RoundedButton("Ajukan Skripsi  →", TEAL, Color.WHITE);
            register.setBorder(BorderFactory.createEmptyBorder(11, 18, 11, 18));
            register.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            register.addActionListener(event -> showPage("studentRegistration"));
            content.add(icon);
            content.add(Box.createVerticalStrut(14));
            content.add(title);
            content.add(Box.createVerticalStrut(7));
            content.add(description);
            content.add(Box.createVerticalStrut(18));
            content.add(register);
            empty.add(content);
            return empty;
        }

        private void refreshStudentStatusCards() {
            if (studentStatusViews.getComponentCount() == 0) {
                return;
            }
            studentStatusCards.removeAll();
            List<ThesisApplication> ownApplications = studentApplications();
            studentStatusCount.setText(ownApplications.size() + " pengajuan");
            if (ownApplications.isEmpty()) {
                ((CardLayout) studentStatusViews.getLayout()).show(studentStatusViews, "empty");
            } else {
                for (ThesisApplication application : ownApplications) {
                    studentStatusCards.add(createStudentStatusCard(application));
                    studentStatusCards.add(Box.createVerticalStrut(12));
                }
                studentStatusCards.add(Box.createVerticalGlue());
                ((CardLayout) studentStatusViews.getLayout()).show(studentStatusViews, "entries");
            }
            studentStatusCards.revalidate();
            studentStatusCards.repaint();
            studentStatusViews.revalidate();
            studentStatusViews.repaint();
        }

        private JPanel createStudentStatusCard(ThesisApplication application) {
            GlassPanel entry = sectionCard();
            entry.setLayout(new BorderLayout(12, 0));
            entry.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
            JPanel accent = new JPanel();
            accent.setBackground(statusColor(application.status));
            accent.setPreferredSize(new Dimension(4, 56));
            entry.add(accent, BorderLayout.WEST);

            JPanel details = new JPanel();
            details.setOpaque(false);
            details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
            JLabel eyebrow = bodyText("JUDUL SKRIPSI");
            eyebrow.setFont(new Font("SansSerif", Font.BOLD, 10));
            eyebrow.setForeground(MUTED);
            JLabel thesisTitle = new JLabel(statusCardTextHtml(application.judul, 560));
            thesisTitle.setFont(new Font("SansSerif", Font.BOLD, 16));
            thesisTitle.setForeground(NAVY);
            JLabel date = bodyText("Diajukan  •  " + displayDate(application.tanggal));
            date.setFont(new Font("SansSerif", Font.PLAIN, 12));
            details.add(eyebrow);
            details.add(Box.createVerticalStrut(6));
            details.add(thesisTitle);
            details.add(Box.createVerticalStrut(8));
            details.add(date);
            JLabel noteText = application.catatan.trim().isEmpty()
                    ? null
                    : new JLabel(statusCardTextHtml(application.catatan.trim(), 536));
            if (!application.catatan.trim().isEmpty()) {
                details.add(Box.createVerticalStrut(13));
                JPanel note = new JPanel(new BorderLayout(0, 5));
                note.setOpaque(true);
                note.setBackground(new Color(239, 247, 250));
                note.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(new Color(216, 233, 239)),
                        BorderFactory.createEmptyBorder(9, 12, 9, 12)));
                JLabel noteTitle = new JLabel("KETERANGAN ADMIN");
                noteTitle.setFont(new Font("SansSerif", Font.BOLD, 10));
                noteTitle.setForeground(TEAL);
                noteText.setFont(new Font("SansSerif", Font.PLAIN, 12));
                noteText.setForeground(NAVY);
                note.add(noteTitle, BorderLayout.NORTH);
                note.add(noteText, BorderLayout.CENTER);
                details.add(note);
            }
            entry.add(details, BorderLayout.CENTER);

            JPanel trailing = new JPanel();
            trailing.setOpaque(false);
            trailing.setLayout(new BoxLayout(trailing, BoxLayout.Y_AXIS));
            JLabel badge = createStatusBadge(application.status);
            badge.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            JButton view = new RoundedButton("Lihat Detail", NAVY, Color.WHITE);
            view.setBorder(BorderFactory.createEmptyBorder(9, 13, 9, 13));
            view.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            view.addActionListener(event -> showApplicationDetails(application));
            trailing.add(badge);
            trailing.add(Box.createVerticalStrut(12));
            trailing.add(view);
            entry.add(trailing, BorderLayout.EAST);
            entry.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentResized(ComponentEvent event) {
                    int textWidth = Math.max(260,
                            entry.getWidth() - entry.getInsets().left - entry.getInsets().right
                                    - accent.getPreferredSize().width - trailing.getPreferredSize().width - 24);
                    thesisTitle.setText(statusCardTextHtml(application.judul, textWidth));
                    if (noteText != null) {
                        noteText.setText(statusCardTextHtml(
                                application.catatan.trim(), Math.max(220, textWidth - 24)));
                    }
                    entry.setMaximumSize(new Dimension(Integer.MAX_VALUE, entry.getPreferredSize().height));
                    entry.revalidate();
                    studentStatusCards.revalidate();
                }
            });
            entry.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            entry.setMaximumSize(new Dimension(Integer.MAX_VALUE, entry.getPreferredSize().height));
            return entry;
        }

        private String statusCardTextHtml(String text, int width) {
            return "<html><div style='width:" + width + "px'>"
                    + escapeHtml(text) + "</div></html>";
        }

        private List<ThesisApplication> studentApplications() {
            List<ThesisApplication> own = new ArrayList<>();
            for (ThesisApplication application : applications) {
                if (application.nim.equalsIgnoreCase(loggedInNim)) {
                    own.add(application);
                }
            }
            own.sort(Comparator.comparing((ThesisApplication application) -> application.tanggal).reversed());
            return own;
        }

        private ThesisApplication latestStudentApplication() {
            List<ThesisApplication> own = studentApplications();
            return own.isEmpty() ? null : own.get(0);
        }

        private String studentDisplayName() {
            if (studentProfile != null && studentProfile.name() != null
                    && !studentProfile.name().isBlank()) {
                return studentProfile.name();
            }
            ThesisApplication latest = latestStudentApplication();
            if (latest != null) {
                return latest.nama;
            }
            try {
                for (Student student : loadStudents()) {
                    if (student.nim.equalsIgnoreCase(loggedInNim)) {
                        return student.nama;
                    }
                }
            } catch (IOException ex) {
                System.err.println("Data profil mahasiswa tidak dapat dibaca: " + ex.getMessage());
            }
            try {
                String accountName = Database.findStudentName(loggedInNim);
                if (accountName != null && !accountName.isBlank()) {
                    return accountName;
                }
            } catch (java.sql.SQLException ex) {
                System.err.println("Data akun mahasiswa tidak dapat dibaca: " + ex.getMessage());
            }
            return "Mahasiswa";
        }

        private JPanel createAdminDashboardPage() {
            JPanel page = new JPanel(new BorderLayout(0, 16));
            page.setOpaque(false);
            page.add(createAdminWelcomeHeader(), BorderLayout.NORTH);

            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            adminMetricCards.setOpaque(false);
            adminMetricCards.setPreferredSize(new Dimension(0, 126));
            adminMetricCards.setMaximumSize(new Dimension(Integer.MAX_VALUE, 126));
            adminMetricCards.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            content.add(adminMetricCards);
            content.add(Box.createVerticalStrut(20));

            GlassPanel overview = sectionCard();
            overview.setLayout(new BorderLayout(0, 16));
            overview.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(220, 233, 243), 1),
                    BorderFactory.createEmptyBorder(20, 20, 16, 20)));
            overview.add(createAdminRecentToolbar(), BorderLayout.NORTH);
            configureAdminDashboardTable();
            adminDashboardResults.add(tableScroll(adminDashboardTable), "results");
            adminDashboardResults.add(createAdminDashboardEmptyState(), "empty");
            adminDashboardResults.setOpaque(false);
            overview.add(adminDashboardResults, BorderLayout.CENTER);

            JPanel footer = new JPanel(new BorderLayout());
            footer.setOpaque(false);
            adminDashboardResultCount.setFont(new Font("SansSerif", Font.PLAIN, 12));
            adminDashboardResultCount.setForeground(MUTED);
            footer.add(adminDashboardResultCount, BorderLayout.WEST);
            JButton viewAll = new RoundedButton("Kelola Semua Pengajuan  →", NAVY, Color.WHITE);
            viewAll.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
            viewAll.addActionListener(e -> openAdminWorkspace("Data Pengajuan Skripsi"));
            footer.add(viewAll, BorderLayout.EAST);
            overview.add(footer, BorderLayout.SOUTH);
            overview.setPreferredSize(new Dimension(0, 430));
            overview.setMinimumSize(new Dimension(0, 330));
            overview.setMaximumSize(new Dimension(Integer.MAX_VALUE, 460));
            overview.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            content.add(overview);
            content.add(Box.createVerticalGlue());
            page.add(content, BorderLayout.CENTER);
            adminDashboardSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshAdminDashboardRows(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshAdminDashboardRows(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshAdminDashboardRows(); }
            });
            adminDashboardStatus.addActionListener(e -> refreshAdminDashboardRows());
            return page;
        }

        private JPanel createAdminWelcomeHeader() {
            JPanel header = new JPanel(new BorderLayout(16, 0));
            header.setOpaque(false);
            JPanel copy = new JPanel();
            copy.setOpaque(false);
            copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
            JLabel title = new JLabel("Selamat Datang, Admin!");
            title.setFont(new Font("SansSerif", Font.BOLD, 29));
            title.setForeground(NAVY);
            JLabel subtitle = bodyText("Kelola dan pantau pengajuan skripsi dengan mudah.");
            subtitle.setFont(new Font("SansSerif", Font.PLAIN, 14));
            copy.add(title);
            copy.add(Box.createVerticalStrut(7));
            copy.add(subtitle);
            header.add(copy, BorderLayout.WEST);

            JLabel date = new JLabel("  " + java.time.LocalDate.now().format(
                    DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", Locale.forLanguageTag("id-ID"))) + "  ");
            date.setFont(new Font("SansSerif", Font.BOLD, 12));
            date.setForeground(new Color(14, 79, 111));
            date.setOpaque(true);
            date.setBackground(new Color(248, 252, 255, 238));
            date.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(195, 220, 236)),
                    BorderFactory.createEmptyBorder(10, 13, 10, 13)));
            header.add(date, BorderLayout.EAST);
            return header;
        }

        private JPanel createAdminRecentToolbar() {
            JPanel toolbar = new JPanel(new BorderLayout(0, 12));
            toolbar.setOpaque(false);
            JPanel titleRow = new JPanel(new BorderLayout(10, 0));
            titleRow.setOpaque(false);
            titleRow.add(new MetricGlyph("≡", TEAL, 38), BorderLayout.WEST);
            JLabel title = sectionTitle("Pengajuan Terbaru");
            title.setFont(new Font("SansSerif", Font.BOLD, 18));
            titleRow.add(title, BorderLayout.CENTER);
            toolbar.add(titleRow, BorderLayout.NORTH);

            JPanel filters = new JPanel(new BorderLayout(12, 0));
            filters.setOpaque(false);
            adminDashboardSearch.setToolTipText("Cari nama mahasiswa atau NIM");
            JPanel searchShell = new RoundedFieldPanel();
            searchShell.setLayout(new BorderLayout(8, 0));
            searchShell.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            searchShell.setPreferredSize(new Dimension(340, 44));
            searchShell.setMinimumSize(new Dimension(170, 44));
            searchShell.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            JLabel searchIcon = new JLabel(new SearchIcon());
            searchIcon.setToolTipText("Cari nama atau NIM");
            searchShell.add(searchIcon, BorderLayout.WEST);
            adminDashboardSearch.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 4));
            adminDashboardSearch.setPreferredSize(new Dimension(280, 38));
            searchShell.add(adminDashboardSearch, BorderLayout.CENTER);
            filters.add(searchShell, BorderLayout.CENTER);
            adminDashboardStatus.setFont(new Font("SansSerif", Font.PLAIN, 12));
            adminDashboardStatus.setPreferredSize(new Dimension(190, 44));
            adminDashboardStatus.setBackground(Color.WHITE);
            filters.add(adminDashboardStatus, BorderLayout.EAST);
            toolbar.add(filters, BorderLayout.CENTER);
            return toolbar;
        }

        private void configureAdminDashboardTable() {
            adminDashboardTable.setRowHeight(46);
            adminDashboardTable.setFillsViewportHeight(true);
            adminDashboardTable.setShowVerticalLines(false);
            adminDashboardTable.setShowHorizontalLines(false);
            adminDashboardTable.setIntercellSpacing(new Dimension(0, 1));
            adminDashboardTable.setGridColor(new Color(232, 240, 247));
            adminDashboardTable.setSelectionBackground(new Color(225, 241, 248));
            adminDashboardTable.setSelectionForeground(NAVY);
            adminDashboardTable.setDefaultRenderer(Object.class, new AdminDashboardCellRenderer());
            adminDashboardTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
            adminDashboardTable.getTableHeader().setForeground(new Color(28, 67, 99));
            adminDashboardTable.getTableHeader().setBackground(new Color(225, 241, 249));
            adminDashboardTable.getTableHeader().setBorder(BorderFactory.createMatteBorder(
                    0, 0, 1, 0, new Color(197, 221, 238)));
            adminDashboardTable.getTableHeader().setPreferredSize(new Dimension(0, 44));
            int[] widths = {48, 150, 112, 235, 140, 132, 96};
            for (int i = 0; i < widths.length; i++) {
                adminDashboardTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
            }
            int actionColumn = adminDashboardTable.getColumnModel().getColumnCount() - 1;
            adminDashboardTable.getColumnModel().getColumn(actionColumn)
                    .setCellRenderer(new DetailButtonRenderer());
            adminDashboardTable.getColumnModel().getColumn(actionColumn)
                    .setCellEditor(new DetailButtonEditor(adminDashboardTable, row -> {
                        if (row >= 0 && row < adminDashboardRows.size()) {
                            showApplicationDetails(adminDashboardRows.get(row));
                        }
                    }));
        }

        private JPanel createAdminDashboardEmptyState() {
            JPanel empty = new JPanel(new GridBagLayout());
            empty.setOpaque(false);
            empty.setPreferredSize(new Dimension(420, 230));
            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            EmptyStateIcon icon = new EmptyStateIcon();
            icon.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            adminDashboardEmptyTitle.setFont(new Font("SansSerif", Font.BOLD, 17));
            adminDashboardEmptyTitle.setForeground(NAVY);
            adminDashboardEmptyTitle.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            adminDashboardEmptyDescription.setFont(new Font("SansSerif", Font.PLAIN, 12));
            adminDashboardEmptyDescription.setForeground(MUTED);
            adminDashboardEmptyDescription.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            JButton reset = new RoundedButton("Hapus Pencarian dan Filter", TEAL, Color.WHITE);
            reset.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
            reset.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            reset.addActionListener(e -> {
                adminDashboardSearch.setText("");
                adminDashboardStatus.setSelectedIndex(0);
                refreshAdminDashboardRows();
            });
            content.add(icon);
            content.add(Box.createVerticalStrut(10));
            content.add(adminDashboardEmptyTitle);
            content.add(Box.createVerticalStrut(6));
            content.add(adminDashboardEmptyDescription);
            content.add(Box.createVerticalStrut(14));
            content.add(reset);
            empty.add(content);
            return empty;
        }

        private void refreshAdminDashboardRows() {
            adminDashboardModel.setRowCount(0);
            adminDashboardRows.clear();
            String query = adminDashboardSearch.getText().trim().toLowerCase(Locale.ROOT);
            String status = String.valueOf(adminDashboardStatus.getSelectedItem());
            int number = 0;
            for (ThesisApplication item : sortedApplications()) {
                String searchable = (item.nama + " " + item.nim).toLowerCase(Locale.ROOT);
                if (!searchable.contains(query)
                        || (!"Semua Status".equals(status) && !item.status.equals(status))) {
                    continue;
                }
                adminDashboardRows.add(item);
                adminDashboardModel.addRow(new Object[]{
                        ++number, item.nama, item.nim, item.judul,
                        displayDate(item.tanggal), item.status, "Detail"
                });
            }
            adminDashboardResultCount.setText(adminDashboardRows.isEmpty()
                    ? applications.isEmpty() ? "Belum ada data pengajuan" : "Tidak ada data yang cocok"
                    : "Menampilkan " + adminDashboardRows.size() + " pengajuan terbaru");
            adminDashboardEmptyTitle.setText(applications.isEmpty()
                    ? "Belum ada pengajuan"
                    : query.isEmpty() && "Semua Status".equals(status)
                            ? "Belum ada pengajuan" : "Tidak ada pengajuan yang cocok");
            adminDashboardEmptyDescription.setText(applications.isEmpty()
                    ? "Data pengajuan akan muncul di sini setelah mahasiswa mendaftar."
                    : "Coba ubah kata kunci pencarian atau filter status.");
            ((CardLayout) adminDashboardResults.getLayout()).show(
                    adminDashboardResults, adminDashboardRows.isEmpty() ? "empty" : "results");
        }

        private void showSelectedRecentDetails(JTable table) {
            int row = table.getSelectedRow();
            List<ThesisApplication> latest = sortedApplications();
            if (row < 0 || row >= Math.min(6, latest.size())) {
                showWarning("Pilih pengajuan terbaru untuk melihat detail.");
                return;
            }
            showApplicationDetails(latest.get(row));
        }

        private long countStatus(String status) {
            return applications.stream().filter(application -> application.status.equals(status)).count();
        }

        private JPanel createAdminSimilarityPage() {
            JPanel page = new JPanel(new BorderLayout(0, 12));
            page.setOpaque(false);
            page.add(createPageHeading("Pemeriksaan Kemiripan Judul",
                    "Setiap pengajuan ditampilkan bersama judul mahasiswa lain yang paling mirip."), BorderLayout.NORTH);

            GlassPanel card = sectionCard();
            card.setLayout(new BorderLayout(0, 14));
            card.setBorder(BorderFactory.createEmptyBorder(18, 20, 18, 20));
            JPanel heading = new JPanel(new BorderLayout(10, 0));
            heading.setOpaque(false);
            JPanel description = new JPanel();
            description.setOpaque(false);
            description.setLayout(new BoxLayout(description, BoxLayout.Y_AXIS));
            JLabel title = sectionTitle("Perbandingan Antarjudul");
            title.setFont(new Font("SansSerif", Font.BOLD, 18));
            description.add(title);
            description.add(Box.createVerticalStrut(5));
            description.add(bodyText("Satu card per pengajuan, diurutkan berdasarkan kemiripan tertinggi."));
            heading.add(description, BorderLayout.CENTER);
            similarityMatchCount.setFont(new Font("SansSerif", Font.BOLD, 12));
            similarityMatchCount.setForeground(MUTED);
            heading.add(similarityMatchCount, BorderLayout.EAST);
            card.add(heading, BorderLayout.NORTH);

            similarityMatchList.setOpaque(false);
            similarityMatchList.setLayout(new BoxLayout(similarityMatchList, BoxLayout.Y_AXIS));
            JScrollPane scroll = new JScrollPane(similarityMatchList,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.getVerticalScrollBar().setUnitIncrement(18);
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
            card.add(scroll, BorderLayout.CENTER);
            page.add(card, BorderLayout.CENTER);
            return page;
        }

        private JPanel createNoSimilarityPairsState() {
            JPanel idle = new JPanel(new GridBagLayout());
            idle.setOpaque(false);
            JPanel content = new JPanel();
            content.setOpaque(false);
            content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
            JLabel title = new JLabel(applications.isEmpty()
                    ? "Belum ada pengajuan skripsi tersimpan"
                    : "Belum ada pasangan judul untuk dibandingkan");
            title.setFont(new Font("SansSerif", Font.BOLD, 17));
            title.setForeground(NAVY);
            title.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
            content.add(title);
            idle.add(content);
            return idle;
        }

        private void openSelectedTitleSimilarity() {
            showPage("adminSimilarity");
        }

        private void refreshSimilarityPairs() {
            List<SimilarityPair> pairs = new ArrayList<>();
            for (ThesisApplication studentTitle : applications) {
                ThesisApplication closestTitle = null;
                double highestScore = -1;
                for (ThesisApplication comparisonTitle : applications) {
                    if (studentTitle == comparisonTitle
                            || studentTitle.nim.equalsIgnoreCase(comparisonTitle.nim)) {
                        continue;
                    }
                    double score = titleSimilarity(studentTitle.judul, comparisonTitle.judul);
                    if (score > highestScore) {
                        highestScore = score;
                        closestTitle = comparisonTitle;
                    }
                }
                pairs.add(new SimilarityPair(
                        studentTitle, closestTitle, Math.max(0, highestScore)));
            }
            pairs.sort(Comparator.comparingDouble((SimilarityPair pair) -> pair.score).reversed());
            similarityMatchCount.setText(pairs.size() + " pengajuan");
            similarityMatchList.removeAll();
            if (pairs.isEmpty()) {
                similarityMatchList.add(createNoSimilarityPairsState());
            } else {
                for (int i = 0; i < pairs.size(); i++) {
                    similarityMatchList.add(createSimilarityPairCard(pairs.get(i), i + 1));
                    if (i < pairs.size() - 1) {
                        similarityMatchList.add(Box.createVerticalStrut(9));
                    }
                }
            }
            similarityMatchList.revalidate();
            similarityMatchList.repaint();
        }

        private JPanel createSimilarityPairCard(SimilarityPair pair, int rank) {
            JPanel card = new JPanel(new BorderLayout(18, 0));
            card.setOpaque(true);
            card.setBackground(Color.WHITE);
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(226, 234, 241)),
                    BorderFactory.createEmptyBorder(16, 18, 16, 18)));
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 190));

            JLabel rankLabel = new JLabel(String.format(Locale.ROOT, "%02d", rank), SwingConstants.CENTER);
            rankLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
            rankLabel.setForeground(TEAL);
            rankLabel.setOpaque(true);
            rankLabel.setBackground(new Color(228, 245, 245));
            rankLabel.setBorder(BorderFactory.createEmptyBorder(7, 9, 7, 9));
            card.add(rankLabel, BorderLayout.WEST);

            JPanel comparison = new JPanel();
            comparison.setOpaque(false);
            comparison.setLayout(new BoxLayout(comparison, BoxLayout.Y_AXIS));
            JLabel studentLabel = bodyText("JUDUL MAHASISWA — " + pair.studentTitle.nama);
            studentLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
            studentLabel.setForeground(TEAL);
            JLabel studentTitle = new JLabel("<html><div style='width:400px'>"
                    + escapeHtml(pair.studentTitle.judul) + "</div></html>");
            studentTitle.setFont(new Font("SansSerif", Font.BOLD, 14));
            studentTitle.setForeground(NAVY);
            comparison.add(studentLabel);
            comparison.add(Box.createVerticalStrut(5));
            comparison.add(studentTitle);
            comparison.add(Box.createVerticalStrut(12));
            if (pair.comparisonTitle == null) {
                JLabel noComparison = bodyText("Belum ada judul dari mahasiswa lain untuk dibandingkan.");
                noComparison.setFont(new Font("SansSerif", Font.PLAIN, 12));
                noComparison.setForeground(MUTED);
                comparison.add(noComparison);
            } else {
                JLabel comparisonLabel = bodyText(
                        "JUDUL PEMBANDING — " + pair.comparisonTitle.nama);
                comparisonLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
                comparisonLabel.setForeground(MUTED);
                JLabel comparisonTitle = new JLabel("<html><div style='width:400px'>"
                        + escapeHtml(pair.comparisonTitle.judul) + "</div></html>");
                comparisonTitle.setFont(new Font("SansSerif", Font.PLAIN, 13));
                comparisonTitle.setForeground(NAVY);
                comparison.add(comparisonLabel);
                comparison.add(Box.createVerticalStrut(5));
                comparison.add(comparisonTitle);
            }
            card.add(comparison, BorderLayout.CENTER);

            SimilarityGauge gauge = new SimilarityGauge(false);
            gauge.setScore(pair.score);
            gauge.setPreferredSize(new Dimension(148, 148));
            gauge.setMinimumSize(new Dimension(148, 148));
            gauge.setMaximumSize(new Dimension(148, 148));
            card.add(gauge, BorderLayout.EAST);
            return card;
        }

        private JPanel createStudentForm() {
            GlassPanel card = sectionCard();
            card.setLayout(new BorderLayout(0, 12));
            card.add(sectionTitle("Formulir Pengajuan"), BorderLayout.NORTH);

            JPanel fields = new JPanel(new GridBagLayout());
            fields.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.weightx = 1;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(3, 0, 5, 0);
            addLabeled(fields, c, "Nama Lengkap", namaInput);
            addLabeled(fields, c, "NIM", nimInput);
            addLabeled(fields, c, "Program Studi", prodiInput);
            addLabeled(fields, c, "Semester", semesterInput);
            addLabeled(fields, c, "Judul Skripsi", judulInput);
            addLabeled(fields, c, "Bidang Penelitian", bidangInput);
            JScrollPane descriptionScroll = new JScrollPane(deskripsiInput);
            descriptionScroll.setPreferredSize(new java.awt.Dimension(300, 90));
            descriptionScroll.setBorder(BorderFactory.createLineBorder(new Color(192, 210, 232)));
            addLabeled(fields, c, "Deskripsi / Latar Belakang Penelitian", descriptionScroll);
            addLabeled(fields, c, "Email (opsional)", emailInput);
            JScrollPane fieldsScroll = new JScrollPane(fields);
            fieldsScroll.setBorder(BorderFactory.createEmptyBorder());
            fieldsScroll.setOpaque(false);
            fieldsScroll.getViewport().setOpaque(false);
            fieldsScroll.getVerticalScrollBar().setUnitIncrement(16);
            card.add(fieldsScroll, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
            buttons.setOpaque(false);
            JButton check = new RoundedButton("Periksa Kemiripan Judul", NAVY, Color.WHITE);
            JButton submit = new RoundedButton("Simpan Pengajuan", TEAL, Color.WHITE);
            JButton reset = new ResetButton();
            check.setBorder(BorderFactory.createEmptyBorder(11, 14, 11, 14));
            submit.setBorder(BorderFactory.createEmptyBorder(11, 16, 11, 16));
            check.addActionListener(e -> checkStudentTitle());
            submit.addActionListener(e -> submitApplication());
            reset.addActionListener(e -> clearStudentForm());
            buttons.add(check);
            buttons.add(submit);
            buttons.add(reset);
            card.add(buttons, BorderLayout.SOUTH);
            return card;
        }

        private JPanel createStudentTracker() {
            GlassPanel card = sectionCard();
            card.setLayout(new BorderLayout(0, 12));
            JPanel top = new JPanel(new BorderLayout(0, 8));
            top.setOpaque(false);
            top.add(sectionTitle("Status Pengajuan Saya"), BorderLayout.NORTH);
            top.add(studentSearch, BorderLayout.CENTER);
            JButton search = new RoundedButton("Cari NIM", NAVY, Color.WHITE);
            search.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
            top.add(search, BorderLayout.EAST);
            JLabel hint = bodyText("Daftar ini hanya menampilkan pengajuan milik NIM akun Anda.");
            top.add(hint, BorderLayout.SOUTH);
            search.addActionListener(e -> refreshStudentRows());
            studentSearch.addActionListener(e -> refreshStudentRows());
            studentTable.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        showSelectedStudentDetails();
                    }
                }
            });
            card.add(top, BorderLayout.NORTH);
            card.add(tableScroll(studentTable), BorderLayout.CENTER);
            JButton viewDetails = new RoundedButton("Lihat Detail Pengajuan", NAVY, Color.WHITE);
            viewDetails.setBorder(BorderFactory.createEmptyBorder(9, 14, 9, 14));
            viewDetails.addActionListener(e -> showSelectedStudentDetails());
            JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            actions.setOpaque(false);
            actions.add(viewDetails);
            card.add(actions, BorderLayout.SOUTH);
            return card;
        }

        private JPanel createAdminPage() {
            JPanel page = new JPanel(new BorderLayout(0, 12));
            page.setOpaque(false);
            JPanel heading = new JPanel();
            heading.setOpaque(false);
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            JPanel headingRow = new JPanel(new BorderLayout(12, 0));
            headingRow.setOpaque(false);
            headingRow.add(new MetricGlyph("✓", TEAL, 42), BorderLayout.WEST);
            headingRow.add(adminPageTitle, BorderLayout.CENTER);
            heading.add(headingRow);
            heading.add(Box.createVerticalStrut(4));
            heading.add(bodyText("Tinjau pengajuan dan kelola proses verifikasi judul skripsi."));
            page.add(heading, BorderLayout.NORTH);

            GlassPanel card = sectionCard();
            card.setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
            card.setLayout(new BorderLayout(0, 14));
            JPanel top = new JPanel();
            top.setOpaque(false);
            top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
            JPanel statRow = new JPanel(new GridLayout(1, 3, 10, 0));
            statRow.setOpaque(false);
            statRow.add(createSmallMetric("Menunggu", pendingMetric));
            statRow.add(createSmallMetric("Disetujui", approvedMetric));
            statRow.add(createSmallMetric("Ditolak", rejectedMetric));
            top.add(statRow);
            top.add(Box.createVerticalStrut(14));

            adminFilterRow.setOpaque(false);
            JPanel searchShell = new RoundedFieldPanel();
            searchShell.setLayout(new BorderLayout(9, 0));
            searchShell.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 10));
            searchShell.setPreferredSize(new Dimension(380, 44));
            searchShell.setMinimumSize(new Dimension(180, 44));
            JLabel searchIcon = new JLabel(new SearchIcon());
            searchIcon.setToolTipText("Cari pengajuan");
            searchShell.add(searchIcon, BorderLayout.WEST);
            adminSearch.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 4));
            adminSearch.setToolTipText("Cari berdasarkan NIM, nama, atau judul skripsi");
            searchShell.add(adminSearch, BorderLayout.CENTER);
            adminFilterRow.add(searchShell, BorderLayout.CENTER);
            adminStatusFilter.setFont(new Font("SansSerif", Font.PLAIN, 12));
            adminStatusFilter.setPreferredSize(new Dimension(205, 44));
            adminStatusFilter.setBackground(Color.WHITE);
            adminStatusFilter.setToolTipText("Filter berdasarkan status pengajuan");
            adminStatusFilter.setVisible(false);
            adminFilterRow.add(adminStatusFilter, BorderLayout.EAST);
            top.add(adminFilterRow);

            adminSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshAdminRows(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshAdminRows(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshAdminRows(); }
            });
            adminStatusFilter.addActionListener(e -> refreshAdminRows());
            card.add(top, BorderLayout.NORTH);

            adminTable.setRowHeight(48);
            adminTable.setFillsViewportHeight(true);
            adminTable.setShowVerticalLines(false);
            adminTable.setIntercellSpacing(new Dimension(0, 1));
            adminTable.getTableHeader().setBackground(new Color(226, 241, 246));
            adminTable.getTableHeader().setPreferredSize(new Dimension(0, 44));
            adminTable.getColumnModel().getColumn(0).setPreferredWidth(120);
            adminTable.getColumnModel().getColumn(1).setPreferredWidth(170);
            adminTable.getColumnModel().getColumn(2).setPreferredWidth(400);
            adminTable.getColumnModel().getColumn(3).setPreferredWidth(150);
            adminTable.getColumnModel().getColumn(4).setPreferredWidth(160);
            card.add(tableScroll(adminTable), BorderLayout.CENTER);

            JButton details = new RoundedButton("Lihat Detail", NAVY, Color.WHITE);
            JButton manageDetails = new RoundedButton("Lihat Detail", NAVY, Color.WHITE);
            JButton similarity = new RoundedButton("Cek Kemiripan Judul", NAVY, Color.WHITE);
            JButton edit = new RoundedButton("Edit Data", NAVY, Color.WHITE);
            JButton approve = new RoundedButton("Setujui", APPROVED, Color.WHITE);
            JButton reject = new RoundedButton("Tolak", REJECTED, Color.WHITE);
            JButton delete = new RoundedButton("Hapus", new Color(101, 114, 132), Color.WHITE);
            for (JButton button : Arrays.asList(
                    details, manageDetails, similarity, edit, approve, reject, delete)) {
                button.setBorder(BorderFactory.createEmptyBorder(11, 16, 11, 16));
                button.setFont(new Font("SansSerif", Font.BOLD, 12));
            }
            adminVerifyActions.setOpaque(false);
            adminManageActions.setOpaque(false);
            adminVerifyActions.add(details);
            adminVerifyActions.add(approve);
            adminVerifyActions.add(reject);
            adminManageActions.add(manageDetails);
            adminManageActions.add(similarity);
            adminManageActions.add(edit);
            adminManageActions.add(delete);
            details.addActionListener(e -> showSelectedAdminDetails());
            manageDetails.addActionListener(e -> showSelectedAdminDetails());
            similarity.addActionListener(e -> openSelectedTitleSimilarity());
            edit.addActionListener(e -> editSelectedApplication());
            approve.addActionListener(e -> changeSelectedStatus("Disetujui"));
            reject.addActionListener(e -> changeSelectedStatus("Ditolak"));
            delete.addActionListener(e -> deleteSelectedApplication());
            JPanel actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0));
            actions.setOpaque(false);
            adminVerifyActions.setVisible(false);
            adminManageActions.setVisible(true);
            actions.add(adminVerifyActions);
            actions.add(adminManageActions);
            adminTable.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent event) {
                    if (event.getClickCount() == 2 && adminTable.getSelectedRow() >= 0) {
                        showSelectedAdminDetails();
                    }
                }
            });
            card.add(actions, BorderLayout.SOUTH);
            page.add(card, BorderLayout.CENTER);
            return page;
        }

        private JPanel createSmallMetric(String title, JLabel value) {
            JPanel box = new JPanel(new BorderLayout(8, 0));
            box.setOpaque(false);
            JLabel label = new JLabel(title);
            label.setFont(new Font("SansSerif", Font.PLAIN, 12));
            label.setForeground(MUTED);
            value.setFont(new Font("SansSerif", Font.BOLD, 20));
            value.setForeground(NAVY);
            box.add(label, BorderLayout.NORTH);
            box.add(value, BorderLayout.CENTER);
            return box;
        }

        private JPanel createPageHeading(String title, String subtitle) {
            JPanel heading = new JPanel();
            heading.setOpaque(false);
            heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
            heading.add(sectionTitle(title));
            heading.add(Box.createVerticalStrut(4));
            heading.add(bodyText(subtitle));
            return heading;
        }

        private void addLabeled(JPanel panel, GridBagConstraints c, String label, JComponent input) {
            JPanel field = new JPanel(new BorderLayout(0, 4));
            field.setOpaque(false);
            JLabel caption = new JLabel(label);
            caption.setFont(new Font("SansSerif", Font.BOLD, 12));
            caption.setForeground(NAVY);
            field.add(caption, BorderLayout.NORTH);
            if (input instanceof JTextField || input instanceof JComboBox<?>) {
                JPanel shell = new RoundedFieldPanel();
                shell.setLayout(new BorderLayout());
                shell.setPreferredSize(new java.awt.Dimension(280, 38));
                shell.add(input, BorderLayout.CENTER);
                field.add(shell, BorderLayout.CENTER);
            } else {
                field.add(input, BorderLayout.CENTER);
            }
            c.gridy++;
            panel.add(field, c);
        }

        private void showPage(String key) {
            if (("Admin".equals(role)
                    && ("adminDashboard".equals(key) || "adminWorkspace".equals(key)
                            || "adminSimilarity".equals(key)))
                    || ("Mahasiswa".equals(role)
                            && ("studentDashboard".equals(key) || "studentStatus".equals(key)))) {
                refreshApplicationsFromDatabase();
            }
            ((CardLayout) pages.getLayout()).show(pages, key);
            activePage = key;
            if ("adminVerify".equals(key)) {
                setActiveSidebarItem(key);
            } else {
                setActiveSidebarItem(key);
            }
            if ("adminWorkspace".equals(key)) {
                refreshAdminRows();
            } else if ("studentStatus".equals(key)) {
                refreshStudentRows();
                refreshStudentStatusCards();
            } else if ("studentDashboard".equals(key)) {
                refreshDashboard();
                refreshStudentRows();
            } else if ("adminDashboard".equals(key)) {
                refreshDashboard();
                refreshAdminRows();
            } else if ("adminSimilarity".equals(key)) {
                refreshSimilarityPairs();
            } else {
                refreshDashboard();
            }
        }

        private void setActiveSidebarItem(String key) {
            for (Map.Entry<String, SidebarButton> entry : sidebarButtons.entrySet()) {
                entry.getValue().setActive(entry.getKey().equals(key)
                        || ("adminVerify".equals(key) && "adminWorkspace".equals(entry.getKey())
                                && "Verifikasi Pengajuan".equals(adminPageTitle.getText())));
            }
        }

        private boolean verifyAdmin() {
            JPasswordField password = new JPasswordField(16);
            int choice = JOptionPane.showConfirmDialog(
                    frame, password, "Verifikasi Admin", JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return false;
            }
            char[] entered = password.getPassword();
            boolean valid;
            try {
                valid = authenticateAdmin(loggedInAdminUsername, entered);
            } catch (java.sql.SQLException ex) {
                showError("Verifikasi admin tidak dapat dilakukan: " + ex.getMessage());
                return false;
            } finally {
                Arrays.fill(entered, '\0');
                password.setText("");
            }
             if (!valid) {
                showError("Password admin tidak sesuai.");
            }
            return valid;
        }

        private void submitApplication() {
            if (!isStudentProfileComplete()) {
                showPage("studentProfile");
                showWarning("Lengkapi profil mahasiswa terlebih dahulu sebelum mengajukan judul skripsi.");
                return;
            }
            String nama = studentProfile.name().trim();
            String nim = studentProfile.nim().trim();
            String email = studentProfile.email().trim();
            String title = judulInput.getText().trim();
            String description = deskripsiInput.getText().trim();
            String semester = "Semester " + studentProfile.semester();
            String bidang = String.valueOf(bidangInput.getSelectedItem());
            String prodi = studentProfile.program().trim();
            if (title.isEmpty() || description.isEmpty() || bidangInput.getSelectedIndex() == 0) {
                showWarning("Judul, deskripsi, dan bidang penelitian wajib diisi.");
                return;
            }
            if (!nim.equalsIgnoreCase(loggedInNim)) {
                showWarning("NIM pengajuan harus sama dengan NIM akun yang sedang masuk.");
                return;
            }
            if (title.length() > 200 || description.length() > 3000) {
                showWarning("Judul maksimal 200 karakter dan deskripsi maksimal 3.000 karakter.");
                return;
            }

            List<SimilarTitle> matches = findSimilarTitles(title, null, loggedInNim);
            if (!matches.isEmpty() && matches.get(0).score > 0.50) {
                JOptionPane.showMessageDialog(
                        frame,
                        formatSimilarityResult(title, matches)
                                + "\n\nPengajuan tidak disimpan. Revisi judul dan periksa kembali.",
                        "Judul Perlu Direvisi",
                        JOptionPane.WARNING_MESSAGE
                );
                return;
            }

            String now = LocalDateTime.now().toString();
            ThesisApplication application = new ThesisApplication(
                    UUID.randomUUID().toString(), nama, nim, prodi, email, semester,
                    title, description, bidang, PENDING_STATUS, "", now);
            if (!matches.isEmpty()) {
                SimilarTitle closest = matches.get(0);
                application.similarityScore = closest.score;
                application.similarityComparedTitle = closest.application.judul;
                application.similarityComparedNim = closest.application.nim;
            }
            applications.add(application);
            if (persist()) {
                studentSearch.setText(nim);
                clearStudentForm();
                refreshAll();
                showPage("studentStatus");
                JOptionPane.showMessageDialog(
                        frame,
                        "Pengajuan berhasil disimpan dengan status Menunggu Verifikasi.",
                        "Pengajuan Tersimpan",
                        JOptionPane.INFORMATION_MESSAGE
                );
            } else {
                applications.remove(application);
            }
        }

        private void checkStudentTitle() {
            String title = judulInput.getText().trim();
            if (title.isEmpty()) {
                showWarning("Masukkan judul skripsi terlebih dahulu.");
                return;
            }
            if (title.length() > 200) {
                showWarning("Judul skripsi maksimal 200 karakter.");
                return;
            }
            List<SimilarTitle> matches = findAllSimilarTitles(title, null, loggedInNim);
            JOptionPane.showMessageDialog(
                    frame,
                    formatSimilarityResult(title, matches),
                    !matches.isEmpty() && matches.get(0).score > 0.50
                            ? "Judul Perlu Direvisi" : "Judul Dapat Dilanjutkan",
                    !matches.isEmpty() && matches.get(0).score > 0.50
                            ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE
            );
        }

        private List<SimilarTitle> findSimilarTitles(String title, String excludedId, String excludedNim) {
            return findSimilarTitles(title, excludedId, excludedNim, 5);
        }

        private List<SimilarTitle> findAllSimilarTitles(
                String title, String excludedId, String excludedNim) {
            return findSimilarTitles(title, excludedId, excludedNim, Integer.MAX_VALUE);
        }

        private List<SimilarTitle> findSimilarTitles(
                String title, String excludedId, String excludedNim, int limit) {
            List<SimilarTitle> matches = new ArrayList<>();
            if (title == null || title.isBlank()) {
                return matches;
            }
            for (ThesisApplication application : applications) {
                if (excludedId != null && excludedId.equals(application.id)) {
                    continue;
                }
                if (excludedNim != null && excludedNim.equalsIgnoreCase(application.nim)) {
                    continue;
                }
                if (application.judul == null || application.judul.isBlank()) {
                    continue;
                }
                double score = titleSimilarity(title, application.judul);
                matches.add(new SimilarTitle(application, score));
            }
            matches.sort(Comparator.comparingDouble((SimilarTitle match) -> match.score).reversed());
            return matches.subList(0, Math.min(limit, matches.size()));
        }

        private void ensureSimilaritySnapshot(ThesisApplication application) {
            if (application.similarityScore != null
                    && application.similarityComparedTitle != null
                    && !application.similarityComparedTitle.isBlank()) {
                return;
            }
            List<SimilarTitle> matches = findSimilarTitles(
                    application.judul, application.id, application.nim, 1);
            if (matches.isEmpty()) {
                return;
            }
            SimilarTitle closest = matches.get(0);
            application.similarityScore = closest.score;
            application.similarityComparedTitle = closest.application.judul;
            application.similarityComparedNim = closest.application.nim;
            persist();
        }

        private String formatSimilarityResult(String title, List<SimilarTitle> matches) {
            if (matches.isEmpty()) {
                return "Judul yang diperiksa:\n" + title
                        + "\n\nBelum ada judul mahasiswa lain yang tersimpan untuk dibandingkan."
                        + "\n\nStatus: Dapat dilanjutkan ke pengajuan.";
            }
            SimilarTitle closest = matches.get(0);
            String decision = closest.score > 0.50
                    ? "PERLU DIREVISI — kemiripan lebih dari 50%."
                    : "DAPAT DILANJUTKAN — kemiripan 50% atau kurang.";
            return "Judul yang diperiksa:\n" + title
                    + "\n\nJudul paling mirip:\n" + closest.application.judul
                    + "\n\nPersentase kemiripan: "
                    + String.format(Locale.ROOT, "%.1f%%", closest.score * 100)
                    + "\n" + decision
                    + "\n\nJudul pembanding lainnya:\n" + formatMatches(matches, "");
        }

        private String formatMatches(List<SimilarTitle> matches, String heading) {
            StringBuilder result = new StringBuilder(heading);
            for (SimilarTitle match : matches) {
                result.append("\n• ")
                        .append(match.application.judul)
                        .append(" — ")
                        .append(String.format(Locale.ROOT, "%.1f%%", match.score * 100))
                        .append(" kemiripan — ")
                        .append(match.application.status);
            }
            return result.toString();
        }

        private static double titleSimilarity(String first, String second) {
            String a = normalizeTitle(first);
            String b = normalizeTitle(second);
            if (a.isEmpty() || b.isEmpty()) {
                return 0;
            }
            if (a.equals(b)) {
                return 1;
            }
            java.util.Set<String> firstTokens = meaningfulTokens(a);
            java.util.Set<String> secondTokens = meaningfulTokens(b);
            java.util.Set<String> intersection = new java.util.HashSet<>(firstTokens);
            intersection.retainAll(secondTokens);
            java.util.Set<String> union = new java.util.HashSet<>(firstTokens);
            union.addAll(secondTokens);
            double tokenJaccard = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
            double tokenDice = firstTokens.isEmpty() && secondTokens.isEmpty()
                    ? 0 : (2.0 * intersection.size()) / (firstTokens.size() + secondTokens.size());
            double characterSimilarity = characterNgramDice(a, b);
            double score = 0.35 * tokenJaccard + 0.45 * tokenDice + 0.20 * characterSimilarity;
            return Math.max(0, Math.min(1, score));
        }

        private static String normalizeTitle(String title) {
            return Normalizer.normalize(title, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "")
                    .toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", " ")
                    .trim()
                    .replaceAll("\\s+", " ");
        }

        private static java.util.Set<String> meaningfulTokens(String normalizedTitle) {
            java.util.Set<String> ignored = java.util.Set.of(
                    "analisis", "pengaruh", "perancangan", "rancang", "bangun", "implementasi",
                    "studi", "kasus", "sistem", "informasi", "aplikasi", "berbasis",
                    "menggunakan", "pada", "untuk", "dengan", "dalam", "dan", "terhadap",
                    "di", "ke", "dari", "yang", "oleh", "sebagai", "metode", "pendekatan"
            );
            java.util.Set<String> tokens = new java.util.HashSet<>(Arrays.asList(normalizedTitle.split(" ")));
            tokens.removeAll(ignored);
            tokens.remove("");
            return tokens;
        }

        private static double characterNgramDice(String first, String second) {
            java.util.List<String> firstGrams = characterNgrams(first);
            java.util.List<String> secondGrams = characterNgrams(second);
            if (firstGrams.isEmpty() || secondGrams.isEmpty()) {
                return 0;
            }
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            for (String gram : firstGrams) {
                counts.merge(gram, 1, Integer::sum);
            }
            int overlap = 0;
            for (String gram : secondGrams) {
                int count = counts.getOrDefault(gram, 0);
                if (count > 0) {
                    overlap++;
                    counts.put(gram, count - 1);
                }
            }
            return (2.0 * overlap) / (firstGrams.size() + secondGrams.size());
        }

        private static java.util.List<String> characterNgrams(String value) {
            String padded = "  " + value + "  ";
            java.util.List<String> grams = new ArrayList<>();
            for (int i = 0; i + 3 <= padded.length(); i++) {
                grams.add(padded.substring(i, i + 3));
            }
            return grams;
        }

        private void changeSelectedStatus(String status) {
            ThesisApplication selected = getSelectedAdminApplication();
            if (selected == null) {
                showWarning("Pilih pengajuan terlebih dahulu.");
                return;
            }
            String confirmation = "Disetujui".equals(status)
                    ? "Setujui pengajuan skripsi dari " + selected.nama + " (" + selected.nim + ")?"
                    : "Tolak pengajuan skripsi dari " + selected.nama + " (" + selected.nim + ")?";
            int confirm = JOptionPane.showConfirmDialog(
                    frame, confirmation, "Konfirmasi " + status,
                    JOptionPane.YES_NO_OPTION,
                    "Ditolak".equals(status) ? JOptionPane.WARNING_MESSAGE : JOptionPane.QUESTION_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
            JTextArea noteInput = createStyledTextArea();
            noteInput.setText(selected.catatan);
            JScrollPane noteScroll = new JScrollPane(noteInput);
            noteScroll.setPreferredSize(new java.awt.Dimension(380, 110));
            JPanel prompt = new JPanel(new BorderLayout(0, 6));
            prompt.add(new JLabel("Alasan penolakan (wajib):"), BorderLayout.NORTH);
            prompt.add(noteScroll, BorderLayout.CENTER);
            String note;
            if ("Ditolak".equals(status)) {
                int choice = JOptionPane.showConfirmDialog(
                        frame, prompt, "Tolak Pengajuan",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
                if (choice != JOptionPane.OK_OPTION) {
                    return;
                }
                note = noteInput.getText().trim();
                if (note.isEmpty()) {
                    showWarning("Alasan penolakan wajib diisi.");
                    return;
                }
            } else {
                note = JOptionPane.showInputDialog(
                        frame, "Catatan untuk mahasiswa (opsional):",
                        "Persetujuan Judul", JOptionPane.QUESTION_MESSAGE);
                if (note == null) {
                    return;
                }
                note = note.trim();
            }
            String previousStatus = selected.status;
            String previousNote = selected.catatan;
            selected.status = status;
            selected.catatan = note;
            StatusHistory historyEntry = null;
            if (!previousStatus.equals(status)) {
                historyEntry = new StatusHistory(
                        selected.id,
                        selected.nim,
                        previousStatus,
                        status,
                        LocalDateTime.now().toString(),
                        "Ditolak".equals(status) ? note : "");
                selected.statusHistory.add(historyEntry);
            }
            if (persist()) {
                refreshAll();
            } else {
                selected.status = previousStatus;
                selected.catatan = previousNote;
                if (historyEntry != null) {
                    selected.statusHistory.remove(historyEntry);
                }
            }
        }

        private void showSelectedAdminDetails() {
            ThesisApplication selected = getSelectedAdminApplication();
            if (selected == null) {
                showWarning("Pilih pengajuan untuk melihat detail.");
                return;
            }
            showApplicationDetails(selected);
        }

        private void showSelectedStudentDetails() {
            int row = studentTable.getSelectedRow();
            List<ThesisApplication> ownApplications = studentApplications();
            if (row < 0 || row >= ownApplications.size()) {
                showWarning("Pilih pengajuan Anda untuk melihat detail.");
                return;
            }
            showApplicationDetails(ownApplications.get(row));
        }

        private void showApplicationDetails(ThesisApplication application) {
            ensureSimilaritySnapshot(application);
            JDialog dialog = new JDialog(frame, "Detail Pengajuan Skripsi", true);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

            JPanel root = new JPanel(new BorderLayout(0, 0));
            root.setBackground(new Color(237, 245, 251));
            root.setBorder(BorderFactory.createLineBorder(new Color(205, 221, 235), 1));

            GradientPanel header = new GradientPanel();
            header.setLayout(new BorderLayout(16, 0));
            header.setBorder(BorderFactory.createEmptyBorder(15, 22, 15, 18));
            header.setPreferredSize(new Dimension(700, 106));
            ImageIcon logo = loadLogo();
            if (logo != null) {
                header.add(new JLabel(new ImageIcon(
                        logo.getImage().getScaledInstance(58, 58, Image.SCALE_SMOOTH))), BorderLayout.WEST);
                dialog.setIconImage(logo.getImage());
            } else {
                header.add(new MetricGlyph("▤", Color.WHITE, 52), BorderLayout.WEST);
            }
            JPanel titleBlock = new JPanel();
            titleBlock.setOpaque(false);
            titleBlock.setLayout(new BoxLayout(titleBlock, BoxLayout.Y_AXIS));
            JLabel university = new JLabel("UNIVERSITAS HASYIM ASY'ARI  •  UNHASY");
            university.setFont(new Font("SansSerif", Font.BOLD, 10));
            university.setForeground(new Color(155, 224, 231));
            JLabel title = new JLabel("Detail Pengajuan Skripsi");
            title.setFont(new Font("SansSerif", Font.BOLD, 21));
            title.setForeground(Color.WHITE);
            JLabel subtitle = new JLabel("Informasi lengkap pengajuan skripsi mahasiswa");
            subtitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
            subtitle.setForeground(new Color(226, 243, 249));
            titleBlock.add(university);
            titleBlock.add(Box.createVerticalStrut(5));
            titleBlock.add(title);
            titleBlock.add(Box.createVerticalStrut(5));
            titleBlock.add(subtitle);
            header.add(titleBlock, BorderLayout.CENTER);

            JButton closeHeader = new JButton("×");
            closeHeader.setFont(new Font("SansSerif", Font.PLAIN, 25));
            closeHeader.setForeground(Color.WHITE);
            closeHeader.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
            closeHeader.setContentAreaFilled(false);
            closeHeader.setFocusPainted(false);
            closeHeader.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            closeHeader.addActionListener(event -> dialog.dispose());
            header.add(closeHeader, BorderLayout.EAST);
            root.add(header, BorderLayout.NORTH);

            JPanel details = new JPanel();
            details.setOpaque(false);
            details.setBorder(BorderFactory.createEmptyBorder(18, 22, 18, 22));
            details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));

            JPanel studentInfo = new JPanel();
            studentInfo.setOpaque(false);
            studentInfo.setLayout(new BoxLayout(studentInfo, BoxLayout.Y_AXIS));
            String[][] studentFields = {
                {"Nama", application.nama},
                {"NIM", application.nim},
                {"Program Studi", application.prodi},
                {"Semester", application.semester},
                {"Email", application.email.isEmpty() ? "—" : application.email}
            };
            for (int i = 0; i < studentFields.length; i++) {
                studentInfo.add(createDetailInfoRow(studentFields[i][0], studentFields[i][1]));
                if (i < studentFields.length - 1) {
                    studentInfo.add(createDetailDivider());
                }
            }
            details.add(createDetailSection("Data Mahasiswa", "●", studentInfo));
            details.add(Box.createVerticalStrut(14));

            details.add(createDetailSection(
                    "Judul Skripsi", "▤", createDetailValuePanel(application.judul, true)));
            details.add(Box.createVerticalStrut(14));

            details.add(createDetailSection(
                    "Kemiripan Judul", "⌕", createSimilarityDetailPanel(application)));
            details.add(Box.createVerticalStrut(14));

            details.add(createDetailSection(
                    "Bidang Penelitian", "◆", createDetailValuePanel(application.bidang, false)));
            details.add(Box.createVerticalStrut(14));

            JTextArea description = createStyledTextArea();
            description.setEditable(false);
            description.setText(application.deskripsi);
            description.setBackground(Color.WHITE);
            description.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
            description.setRows(5);
            description.setCaretPosition(0);
            JScrollPane descriptionScroll = new JScrollPane(description);
            descriptionScroll.setBorder(BorderFactory.createLineBorder(new Color(222, 233, 242)));
            descriptionScroll.setPreferredSize(new Dimension(440, 142));
            descriptionScroll.getVerticalScrollBar().setUnitIncrement(16);
            details.add(createDetailSection("Deskripsi / Latar Belakang", "i", descriptionScroll));
            details.add(Box.createVerticalStrut(14));

            JPanel metadata = new JPanel(new GridLayout(1, 2, 12, 0));
            metadata.setOpaque(false);
            metadata.add(createDetailMetadataTile("Tanggal Pengajuan",
                    displayDate(application.tanggal), "▦"));
            JPanel statusTile = createDetailMetadataTile("Status", "", "▥");
            statusTile.add(createStatusBadge(application.status), BorderLayout.SOUTH);
            metadata.add(statusTile);
            metadata.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            details.add(createDetailSection("Informasi Pengajuan", "▦", metadata));

            if (!application.catatan.isEmpty()) {
                details.add(Box.createVerticalStrut(14));
                details.add(createDetailSection(
                        "Catatan / Alasan Admin", "●", createDetailValuePanel(application.catatan, false)));
            }
            details.add(Box.createVerticalStrut(14));
            details.add(createDetailSection(
                    "Riwayat Status", "◷", createStatusHistoryPanel(application)));

            JScrollPane detailScroll = new JScrollPane(details,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            detailScroll.setBorder(BorderFactory.createEmptyBorder());
            detailScroll.setOpaque(false);
            detailScroll.getViewport().setOpaque(false);
            detailScroll.getVerticalScrollBar().setUnitIncrement(20);
            detailScroll.getVerticalScrollBar().setPreferredSize(new Dimension(11, 0));
            root.add(detailScroll, BorderLayout.CENTER);

            JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
            footer.setBackground(Color.WHITE);
            footer.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(222, 233, 242)),
                    BorderFactory.createEmptyBorder(12, 22, 14, 22)));
            JButton close = new RoundedButton("Tutup", NAVY, Color.WHITE);
            close.setBorder(BorderFactory.createEmptyBorder(11, 28, 11, 28));
            close.addActionListener(event -> dialog.dispose());
            footer.add(close);
            root.add(footer, BorderLayout.SOUTH);

            dialog.setContentPane(root);
            java.awt.Rectangle usable = java.awt.GraphicsEnvironment
                    .getLocalGraphicsEnvironment().getMaximumWindowBounds();
            int width = Math.max(440, Math.min(780, usable.width - 48));
            int height = Math.max(480, Math.min(760, usable.height - 64));
            dialog.setSize(width, height);
            dialog.setMinimumSize(new Dimension(Math.min(560, width), Math.min(480, height)));
            dialog.setLocationRelativeTo(frame);
            dialog.setVisible(true);
        }

        private JPanel createStatusHistoryPanel(ThesisApplication application) {
            if (application.statusHistory.isEmpty()) {
                return createDetailValuePanel(
                        "Belum ada perubahan status yang tercatat untuk pengajuan ini.", false);
            }

            JPanel history = new JPanel();
            history.setOpaque(false);
            history.setLayout(new BoxLayout(history, BoxLayout.Y_AXIS));
            for (int i = application.statusHistory.size() - 1; i >= 0; i--) {
                StatusHistory entry = application.statusHistory.get(i);
                JPanel item = new DetailCardPanel(new Color(241, 247, 252), new BorderLayout(0, 7));
                item.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

                JLabel transition = new JLabel("<html><b>" + escapeHtml(entry.previousStatus)
                        + "</b> &nbsp;→&nbsp; <b>" + escapeHtml(entry.newStatus) + "</b></html>");
                transition.setFont(new Font("SansSerif", Font.PLAIN, 13));
                transition.setForeground(statusColor(entry.newStatus));
                item.add(transition, BorderLayout.NORTH);

                JPanel details = new JPanel();
                details.setOpaque(false);
                details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
                JLabel nim = bodyText("NIM saat perubahan: " + entry.nim);
                nim.setFont(new Font("SansSerif", Font.PLAIN, 10));
                JLabel timestamp = bodyText("Diubah " + displayDate(entry.changedAt));
                timestamp.setFont(new Font("SansSerif", Font.PLAIN, 11));
                details.add(nim);
                details.add(Box.createVerticalStrut(3));
                details.add(timestamp);
                if (!entry.note.isBlank()) {
                    JLabel note = new JLabel("<html><div style='width:490px'>"
                            + escapeHtml(entry.note) + "</div></html>");
                    note.setFont(new Font("SansSerif", Font.PLAIN, 12));
                    note.setForeground(NAVY);
                    details.add(Box.createVerticalStrut(6));
                    details.add(note);
                }
                item.add(details, BorderLayout.CENTER);
                item.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
                item.setMaximumSize(new Dimension(Integer.MAX_VALUE, item.getPreferredSize().height));
                history.add(item);
                if (i > 0) {
                    history.add(Box.createVerticalStrut(8));
                }
            }
            history.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            return history;
        }

        private JPanel createSimilarityDetailPanel(ThesisApplication application) {
            if (application.similarityScore == null
                    || application.similarityComparedTitle == null
                    || application.similarityComparedTitle.isBlank()) {
                return createDetailValuePanel(
                        "Persentase belum tersedia karena belum ada judul pengajuan mahasiswa lain "
                                + "yang dapat dibandingkan.",
                        false);
            }

            boolean exceedsLimit = application.similarityScore > 0.50;
            Color accent = exceedsLimit ? REJECTED : APPROVED;
            JPanel card = new DetailCardPanel(new Color(241, 247, 252), new BorderLayout(14, 0));
            card.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));

            JLabel percentage = new JLabel(String.format(
                    Locale.ROOT, "%.1f%%", application.similarityScore * 100));
            percentage.setFont(new Font("SansSerif", Font.BOLD, 28));
            percentage.setForeground(accent);
            percentage.setHorizontalAlignment(SwingConstants.CENTER);
            percentage.setPreferredSize(new Dimension(86, 54));
            card.add(percentage, BorderLayout.WEST);

            JPanel comparison = new JPanel();
            comparison.setOpaque(false);
            comparison.setLayout(new BoxLayout(comparison, BoxLayout.Y_AXIS));
            JLabel threshold = new JLabel(exceedsLimit
                    ? "Melewati batas kemiripan 50%"
                    : "Di bawah atau sama dengan batas 50%");
            threshold.setFont(new Font("SansSerif", Font.BOLD, 12));
            threshold.setForeground(accent);
            JLabel comparedWith = new JLabel("Judul pembanding paling mirip");
            comparedWith.setFont(new Font("SansSerif", Font.BOLD, 11));
            comparedWith.setForeground(MUTED);
            JLabel comparedTitle = new JLabel("<html><div style='width:360px'>"
                    + escapeHtml(application.similarityComparedTitle) + "</div></html>");
            comparedTitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
            comparedTitle.setForeground(NAVY);
            comparison.add(threshold);
            comparison.add(Box.createVerticalStrut(5));
            comparison.add(comparedWith);
            comparison.add(Box.createVerticalStrut(3));
            comparison.add(comparedTitle);
            if (application.similarityComparedNim != null
                    && !application.similarityComparedNim.isBlank()) {
                JLabel comparedNim = bodyText("NIM pembanding: " + application.similarityComparedNim);
                comparedNim.setFont(new Font("SansSerif", Font.PLAIN, 10));
                comparison.add(Box.createVerticalStrut(3));
                comparison.add(comparedNim);
            }
            card.add(comparison, BorderLayout.CENTER);
            card.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
            return card;
        }

        private JPanel createDetailSection(String title, String glyph, JComponent content) {
            JPanel section = createDetailCard(new BorderLayout(0, 12));
            section.setBorder(BorderFactory.createEmptyBorder(15, 17, 16, 17));
            section.add(createDetailSectionTitle(title, glyph), BorderLayout.NORTH);
            section.add(content, BorderLayout.CENTER);
            section.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            return section;
        }

        private JPanel createDetailSectionTitle(String text, String glyph) {
            JPanel heading = new JPanel(new BorderLayout(8, 0));
            heading.setOpaque(false);
            heading.add(new MetricGlyph(glyph, TEAL, 26), BorderLayout.WEST);
            JLabel title = new JLabel(text);
            title.setFont(new Font("SansSerif", Font.BOLD, 14));
            title.setForeground(NAVY);
            heading.add(title, BorderLayout.CENTER);
            heading.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            return heading;
        }

        private JPanel createDetailInfoRow(String label, String value) {
            JPanel row = new JPanel(new GridBagLayout());
            row.setOpaque(false);
            JLabel key = new JLabel(label);
            key.setFont(new Font("SansSerif", Font.BOLD, 12));
            key.setForeground(new Color(43, 74, 103));
            key.setPreferredSize(new Dimension(126, 28));
            JLabel content = new JLabel("<html><div style='width:340px'>"
                    + escapeHtml(value) + "</div></html>");
            content.setFont(new Font("SansSerif", Font.PLAIN, 12));
            content.setForeground(NAVY);
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = 0;
            c.anchor = GridBagConstraints.WEST;
            c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(3, 0, 3, 8);
            c.weightx = 0;
            row.add(key, c);
            c.gridx = 1;
            c.weightx = 1;
            c.insets = new Insets(3, 0, 3, 0);
            row.add(content, c);
            row.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
            return row;
        }

        private JPanel createDetailValuePanel(String value, boolean emphasized) {
            JPanel panel = new DetailCardPanel(new Color(239, 247, 252));
            panel.setLayout(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(12, 15, 12, 15));
            JLabel text = new JLabel("<html><div style='width:400px'>"
                    + escapeHtml(value) + "</div></html>");
            text.setFont(new Font("SansSerif", emphasized ? Font.BOLD : Font.PLAIN, 13));
            text.setForeground(NAVY);
            panel.add(text, BorderLayout.CENTER);
            panel.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
            return panel;
        }

        private JSeparator createDetailDivider() {
            JSeparator divider = new JSeparator();
            divider.setForeground(new Color(230, 238, 245));
            divider.setBackground(new Color(230, 238, 245));
            divider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
            return divider;
        }

        private JPanel createDetailMetadataTile(String title, String value, String glyph) {
            JPanel tile = new DetailCardPanel(new Color(241, 247, 252), new BorderLayout(10, 5));
            tile.setBorder(BorderFactory.createEmptyBorder(11, 13, 11, 13));
            JPanel heading = new JPanel(new BorderLayout(8, 0));
            heading.setOpaque(false);
            heading.add(new MetricGlyph(glyph, TEAL, 22), BorderLayout.WEST);
            JLabel headingTitle = new JLabel(title);
            headingTitle.setFont(new Font("SansSerif", Font.BOLD, 11));
            headingTitle.setForeground(NAVY);
            heading.add(headingTitle, BorderLayout.CENTER);
            tile.add(heading, BorderLayout.NORTH);
            if (!value.isEmpty()) {
                JLabel content = new JLabel(value);
                content.setFont(new Font("SansSerif", Font.PLAIN, 12));
                content.setForeground(new Color(43, 74, 103));
                content.setBorder(BorderFactory.createEmptyBorder(2, 32, 0, 0));
                tile.add(content, BorderLayout.CENTER);
            }
            return tile;
        }

        private JPanel createDetailCard(java.awt.LayoutManager layout) {
            return new DetailCardPanel(Color.WHITE, layout);
        }

        private static final class DetailCardPanel extends JPanel {
            private final Color fillColor;

            DetailCardPanel(Color fillColor) {
                this(fillColor, new BorderLayout());
            }

            DetailCardPanel(Color fillColor, java.awt.LayoutManager layout) {
                super(layout);
                this.fillColor = fillColor;
                setOpaque(false);
            }

            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(22, 56, 83, 13));
                g2.fillRoundRect(1, 3, getWidth() - 3, getHeight() - 4, 20, 20);
                g2.setColor(fillColor);
                g2.fillRoundRect(0, 0, getWidth() - 2, getHeight() - 5, 18, 18);
                g2.setColor(new Color(218, 231, 242));
                g2.drawRoundRect(0, 0, getWidth() - 3, getHeight() - 6, 18, 18);
                g2.dispose();
                super.paintComponent(graphics);
            }
        }

        private void editSelectedApplication() {
            ThesisApplication selected = getSelectedAdminApplication();
            if (selected == null) {
                showWarning("Pilih pengajuan yang ingin dikelola.");
                return;
            }
            JTextField name = createStyledTextField(selected.nama);
            JTextField nim = createStyledTextField(selected.nim);
            JTextField email = createStyledTextField(selected.email);
            JTextField title = createStyledTextField(selected.judul);
            JComboBox<String> prodi = createChoice(
                    "Pilih program studi",
                    "Sistem Informasi",
                    "Teknik Informatika",
                    "Pendidikan Agama Islam",
                    "Pendidikan Guru Sekolah Dasar",
                    "Ekonomi Syariah",
                    "Hukum Keluarga Islam",
                    "Manajemen Pendidikan Islam");
            prodi.setSelectedItem(selected.prodi);
            JComboBox<String> semester = createChoice(
                    "Pilih semester",
                    "Semester 6", "Semester 7", "Semester 8", "Semester 9",
                    "Semester 10", "Semester 11", "Semester 12");
            semester.setSelectedItem(selected.semester);
            JComboBox<String> bidang = createChoice(
                    "Pilih bidang penelitian",
                    "Sistem Informasi", "Rekayasa Perangkat Lunak",
                    "Kecerdasan Buatan dan Data", "Jaringan dan Keamanan",
                    "Pendidikan", "Ekonomi dan Bisnis", "Hukum", "Lainnya");
            bidang.setSelectedItem(selected.bidang);
            JTextArea description = createStyledTextArea();
            description.setText(selected.deskripsi);
            JScrollPane descriptionScroll = new JScrollPane(description);
            descriptionScroll.setPreferredSize(new java.awt.Dimension(420, 95));
            JPanel fields = new JPanel(new GridLayout(0, 2, 8, 7));
            fields.add(new JLabel("Nama"));
            fields.add(name);
            fields.add(new JLabel("NIM"));
            fields.add(nim);
            fields.add(new JLabel("Program Studi"));
            fields.add(prodi);
            fields.add(new JLabel("Semester"));
            fields.add(semester);
            fields.add(new JLabel("Email"));
            fields.add(email);
            fields.add(new JLabel("Judul Skripsi"));
            fields.add(title);
            fields.add(new JLabel("Bidang Penelitian"));
            fields.add(bidang);
            fields.add(new JLabel("Deskripsi / Latar Belakang"));
            fields.add(descriptionScroll);
            int choice = JOptionPane.showConfirmDialog(
                    frame, fields, "Edit Pengajuan Skripsi",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (choice != JOptionPane.OK_OPTION) {
                return;
            }
            if (name.getText().trim().isEmpty() || nim.getText().trim().isEmpty()
                    || title.getText().trim().isEmpty() || description.getText().trim().isEmpty()
                    || prodi.getSelectedIndex() == 0 || semester.getSelectedIndex() == 0
                    || bidang.getSelectedIndex() == 0) {
                showWarning("Nama, NIM, program studi, semester, judul, deskripsi, dan bidang wajib diisi.");
                return;
            }
            if ((!email.getText().trim().isEmpty()
                    && !email.getText().trim().matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
                    || title.getText().trim().length() > 200
                    || description.getText().trim().length() > 3000) {
                showWarning("Periksa format email, judul (maksimal 200 karakter), dan deskripsi (maksimal 3.000 karakter).");
                return;
            }
            String previousName = selected.nama;
            String previousNim = selected.nim;
            String previousProdi = selected.prodi;
            String previousEmail = selected.email;
            String previousSemester = selected.semester;
            String previousTitle = selected.judul;
            String previousDescription = selected.deskripsi;
            String previousField = selected.bidang;
            Double previousSimilarityScore = selected.similarityScore;
            String previousComparedTitle = selected.similarityComparedTitle;
            String previousComparedNim = selected.similarityComparedNim;
            selected.nama = name.getText().trim();
            selected.nim = nim.getText().trim();
            selected.prodi = String.valueOf(prodi.getSelectedItem());
            selected.email = email.getText().trim();
            selected.semester = String.valueOf(semester.getSelectedItem());
            selected.judul = title.getText().trim();
            selected.deskripsi = description.getText().trim();
            selected.bidang = String.valueOf(bidang.getSelectedItem());
            if (!previousTitle.equals(selected.judul) || !previousNim.equalsIgnoreCase(selected.nim)) {
                selected.similarityScore = null;
                selected.similarityComparedTitle = null;
                selected.similarityComparedNim = null;
            }
            if (persist()) {
                refreshAll();
            } else {
                selected.nama = previousName;
                selected.nim = previousNim;
                selected.prodi = previousProdi;
                selected.email = previousEmail;
                selected.semester = previousSemester;
                selected.judul = previousTitle;
                selected.deskripsi = previousDescription;
                selected.bidang = previousField;
                selected.similarityScore = previousSimilarityScore;
                selected.similarityComparedTitle = previousComparedTitle;
                selected.similarityComparedNim = previousComparedNim;
            }
        }

        private void deleteSelectedApplication() {
            ThesisApplication selected = getSelectedAdminApplication();
            if (selected == null) {
                showWarning("Pilih pengajuan yang ingin dihapus.");
                return;
            }
            int confirm = JOptionPane.showConfirmDialog(
                    frame, "Hapus pengajuan \"" + selected.judul + "\"?",
                    "Konfirmasi Hapus", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm == JOptionPane.YES_OPTION) {
                int index = applications.indexOf(selected);
                applications.remove(selected);
                if (persist()) {
                    refreshAll();
                } else if (index >= 0) {
                    applications.add(index, selected);
                }
            }
        }

        private ThesisApplication getSelectedAdminApplication() {
            int selectedRow = adminTable.getSelectedRow();
            return selectedRow < 0 || selectedRow >= adminRows.size()
                    ? null : adminRows.get(selectedRow);
        }

        private void refreshAll() {
            refreshDashboard();
            refreshAdminRows();
            refreshStudentRows();
            refreshStudentStatusCards();
        }

        private void refreshApplicationsFromDatabase() {
            if (!frame.isVisible() || (applicationsRefreshTimer != null && !frame.isActive())) {
                return;
            }
            try {
                List<ThesisApplication> refreshed = loadApplications();
                if (toDatabaseApplications(applications).equals(toDatabaseApplications(refreshed))) {
                    return;
                }
                applications.clear();
                applications.addAll(refreshed);
                externalReloadErrorShown = false;
                refreshAll();
            } catch (IOException ex) {
                if (!externalReloadErrorShown) {
                    showError("Data pengajuan terbaru tidak dapat dimuat: " + ex.getMessage());
                    externalReloadErrorShown = true;
                }
            }
        }

        private void refreshDashboard() {
            long waiting = applications.stream().filter(a -> a.status.equals(PENDING_STATUS)).count();
            long approved = applications.stream().filter(a -> a.status.equals("Disetujui")).count();
            long rejected = applications.stream().filter(a -> a.status.equals("Ditolak")).count();
            pendingMetric.setText(String.valueOf(waiting));
            approvedMetric.setText(String.valueOf(approved));
            rejectedMetric.setText(String.valueOf(rejected));
            totalMetric.setText("Total " + applications.size() + " pengajuan");
            pendingSummary.setText(waiting + " pengajuan menunggu ditinjau");

            studentWelcome.setText("Selamat Datang, " + studentDisplayName() + "!");
            List<ThesisApplication> ownApplications = "Mahasiswa".equals(role)
                    ? studentApplications() : List.of();
            long ownWaiting = ownApplications.stream()
                    .filter(application -> application.status.equals(PENDING_STATUS)).count();
            long ownApproved = ownApplications.stream()
                    .filter(application -> application.status.equals("Disetujui")).count();
            long ownRejected = ownApplications.stream()
                    .filter(application -> application.status.equals("Ditolak")).count();
            studentMetricCards.removeAll();
            studentMetricCards.add(createStudentMetricCard(
                    "Total Pengajuan", String.valueOf(ownApplications.size()), "Pengajuan Anda",
                    new Color(46, 132, 232), "▤"));
            studentMetricCards.add(createStudentMetricCard(
                    "Disetujui", String.valueOf(ownApproved), "Judul disetujui",
                    APPROVED, "✓"));
            studentMetricCards.add(createStudentMetricCard(
                    "Ditolak", String.valueOf(ownRejected), "Perlu tindak lanjut",
                    REJECTED, "×"));
            studentMetricCards.add(createStudentMetricCard(
                    "Menunggu Verifikasi", String.valueOf(ownWaiting), "Sedang ditinjau",
                    WAITING, "◷"));
            studentMetricCards.revalidate();
            studentMetricCards.repaint();
            if ("Mahasiswa".equals(role)) {
                refreshStudentDashboardLatestCard();
                refreshStudentDashboardRows();
            }

            adminMetricCards.removeAll();
            adminMetricCards.add(createAdminMetricCard(
                    "Total Pengajuan", String.valueOf(applications.size()), "Semua pengajuan",
                    new Color(46, 132, 232), "▤"));
            adminMetricCards.add(createAdminMetricCard(
                    "Menunggu", String.valueOf(waiting), "Perlu ditinjau",
                    WAITING, "◷"));
            adminMetricCards.add(createAdminMetricCard(
                    "Disetujui", String.valueOf(approved), "Judul disetujui",
                    APPROVED, "✓"));
            adminMetricCards.add(createAdminMetricCard(
                    "Ditolak", String.valueOf(rejected), "Perlu tindak lanjut",
                    REJECTED, "×"));
            adminMetricCards.revalidate();
            adminMetricCards.repaint();
            if ("Admin".equals(role) && adminDashboardResults.getComponentCount() > 0) {
                refreshAdminDashboardRows();
            }

            homeMetrics.removeAll();
            homeMetrics.add(createMetricCard("Menunggu", String.valueOf(waiting), "Perlu ditinjau"));
            homeMetrics.add(createMetricCard("Disetujui", String.valueOf(approved), "Judul disetujui"));
            homeMetrics.add(createMetricCard("Ditolak", String.valueOf(rejected), "Perlu perbaikan"));
            homeMetrics.revalidate();
            homeMetrics.repaint();

            recentModel.setRowCount(0);
            List<ThesisApplication> recent = sortedApplications();
            for (int i = 0; i < Math.min(6, recent.size()); i++) {
                ThesisApplication item = recent.get(i);
                recentModel.addRow(new Object[]{item.nama, item.nim, item.judul, item.status});
            }
        }

        private JPanel createMetricCard(String title, String value, String caption) {
            GlassPanel card = sectionCard();
            card.setLayout(new BorderLayout(14, 0));
            Color accent = title.contains("DITOLAK") ? REJECTED
                    : title.contains("MENUNGGU") || title.contains("Menunggu") ? WAITING
                    : title.contains("DISETUJUI") || title.contains("STATUS TERBARU") ? APPROVED : TEAL;
            String glyph = accent == REJECTED ? "×" : accent == WAITING ? "◷"
                    : accent == APPROVED ? "✓" : "▤";
            MetricGlyph icon = new MetricGlyph(glyph, accent);
            card.add(icon, BorderLayout.WEST);
            JPanel labels = new JPanel();
            labels.setOpaque(false);
            labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
            JLabel label = new JLabel(title.toUpperCase(Locale.ROOT));
            label.setFont(new Font("SansSerif", Font.BOLD, 11));
            label.setForeground(MUTED);
            JLabel number = new JLabel(value);
            number.setFont(new Font("SansSerif", Font.BOLD, 27));
            number.setForeground(NAVY);
            JLabel foot = bodyText(caption);
            labels.add(label);
            labels.add(Box.createVerticalStrut(5));
            labels.add(number);
            labels.add(Box.createVerticalStrut(3));
            labels.add(foot);
            card.add(labels, BorderLayout.CENTER);
            return card;
        }

        private JPanel createAdminMetricCard(
                String title, String value, String caption, Color accent, String glyph) {
            JPanel card = new AdminMetricCardPanel(accent);
            card.setLayout(new BorderLayout(14, 0));
            card.setBorder(BorderFactory.createEmptyBorder(17, 16, 15, 16));
            card.add(new MetricGlyph(glyph, accent, 58), BorderLayout.WEST);

            JPanel labels = new JPanel();
            labels.setOpaque(false);
            labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
            JLabel label = new JLabel(title.toUpperCase(Locale.ROOT));
            label.setFont(new Font("SansSerif", Font.BOLD, 10));
            label.setForeground(new Color(95, 119, 142));
            JLabel number = new JLabel(value);
            number.setFont(new Font("SansSerif", Font.BOLD, 30));
            number.setForeground(NAVY);
            JLabel foot = bodyText(caption);
            foot.setFont(new Font("SansSerif", Font.PLAIN, 11));
            labels.add(label);
            labels.add(Box.createVerticalStrut(5));
            labels.add(number);
            labels.add(Box.createVerticalStrut(3));
            labels.add(foot);
            card.add(labels, BorderLayout.CENTER);
            return card;
        }

        private JPanel createStudentMetricCard(
                String title, String value, String caption, Color accent, String glyph) {
            JPanel card = new AdminMetricCardPanel(accent);
            card.setLayout(new BorderLayout(10, 0));
            card.setBorder(BorderFactory.createEmptyBorder(11, 12, 10, 12));
            card.add(new MetricGlyph(glyph, accent, 42), BorderLayout.WEST);

            JPanel labels = new JPanel();
            labels.setOpaque(false);
            labels.setLayout(new BoxLayout(labels, BoxLayout.Y_AXIS));
            JLabel label = new JLabel("<html><div style='width:92px'>"
                    + escapeHtml(title.toUpperCase(Locale.ROOT)) + "</div></html>");
            label.setFont(new Font("SansSerif", Font.BOLD, 9));
            label.setForeground(MUTED);
            JLabel number = new JLabel(value);
            number.setFont(new Font("SansSerif", Font.BOLD, 24));
            number.setForeground(NAVY);
            JLabel foot = bodyText(caption);
            foot.setFont(new Font("SansSerif", Font.PLAIN, 10));
            labels.add(label);
            labels.add(Box.createVerticalStrut(4));
            labels.add(number);
            labels.add(Box.createVerticalStrut(2));
            labels.add(foot);
            card.add(labels, BorderLayout.CENTER);
            return card;
        }

        private void refreshStudentRows() {
            studentModel.setRowCount(0);
            for (ThesisApplication item : studentApplications()) {
                studentModel.addRow(new Object[]{
                        item.judul, item.bidang, displayDate(item.tanggal), item.status,
                        item.catatan.isEmpty() ? "—" : item.catatan
                });
            }
        }

        private void refreshAdminRows() {
            adminModel.setRowCount(0);
            adminRows.clear();
            String query = adminSearch.getText().trim().toLowerCase(Locale.ROOT);
            String selectedStatus = String.valueOf(adminStatusFilter.getSelectedItem());
            boolean verificationPage = "Verifikasi Pengajuan Skripsi".equals(adminPageTitle.getText());
            for (ThesisApplication item : sortedApplications()) {
                String searchable = verificationPage
                        ? (item.nim + " " + item.nama + " " + item.judul)
                        : (item.nim + " " + item.nama);
                searchable = searchable.toLowerCase(Locale.ROOT);
                boolean matchesStatus = !verificationPage || "Semua Status".equals(selectedStatus)
                        || item.status.equals(selectedStatus);
                if (!searchable.contains(query) || !matchesStatus) {
                    continue;
                }
                adminRows.add(item);
                adminModel.addRow(new Object[]{
                        item.nim, item.nama, item.judul, displayDate(item.tanggal), item.status
                });
            }
        }

        private List<ThesisApplication> sortedApplications() {
            List<ThesisApplication> sorted = new ArrayList<>(applications);
            sorted.sort(Comparator.comparing((ThesisApplication a) -> a.tanggal).reversed());
            return sorted;
        }

        private void clearStudentForm() {
            populateRegistrationProfileFields();
            judulInput.setText("");
            deskripsiInput.setText("");
            bidangInput.setSelectedIndex(0);
        }

        private boolean persist() {
            try {
                saveApplications(applications);
                return true;
            } catch (IOException ex) {
                showError("Data tidak dapat disimpan: " + ex.getMessage());
                return false;
            }
        }

        private void showWarning(String message) {
            JOptionPane.showMessageDialog(frame, message, "Perhatian", JOptionPane.WARNING_MESSAGE);
        }

        private void showError(String message) {
            JOptionPane.showMessageDialog(frame, message, "Terjadi Kesalahan", JOptionPane.ERROR_MESSAGE);
        }

        private static GlassPanel sectionCard() {
            GlassPanel panel = new GlassPanel();
            panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
            return panel;
        }

        private static String displayDate(String value) {
            try {
                return LocalDateTime.parse(value).format(DATE_FORMAT);
            } catch (java.time.format.DateTimeParseException ex) {
                return value;
            }
        }

        private static String escapeHtml(String value) {
            return value.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                    .replace("\"", "&quot;")
                    .replace("'", "&#39;");
        }

        private static JLabel sectionTitle(String text) {
            JLabel label = new JLabel(text);
            label.setFont(new Font("SansSerif", Font.BOLD, 20));
            label.setForeground(NAVY);
            return label;
        }

        private static JLabel bodyText(String text) {
            JLabel label = new JLabel(text);
            label.setFont(new Font("SansSerif", Font.PLAIN, 12));
            label.setForeground(MUTED);
            return label;
        }

        private static JTextField createStyledTextField(String placeholder) {
            JTextField field = createInput(placeholder);
            field.setFont(new Font("SansSerif", Font.PLAIN, 13));
            return field;
        }

        private static JTextArea createStyledTextArea() {
            JTextArea area = new JTextArea(4, 28);
            area.setFont(new Font("SansSerif", Font.PLAIN, 13));
            area.setForeground(NAVY);
            area.setCaretColor(TEAL);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            area.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
            return area;
        }

        private static DefaultTableModel readOnlyTableModel(String... columns) {
            return new DefaultTableModel(columns, 0) {
                @Override
                public boolean isCellEditable(int row, int column) {
                    return false;
                }
            };
        }

        private static JTable createStyledTable(DefaultTableModel model) {
            JTable table = new JTable(model);
            table.setFont(new Font("SansSerif", Font.PLAIN, 13));
            table.setForeground(NAVY);
            table.setRowHeight(40);
            table.setShowVerticalLines(false);
            table.setShowHorizontalLines(true);
            table.setIntercellSpacing(new Dimension(0, 1));
            table.setGridColor(new Color(238, 243, 247));
            table.setSelectionBackground(new Color(226, 242, 243));
            table.setSelectionForeground(NAVY);
            table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 12));
            table.getTableHeader().setForeground(NAVY);
            table.getTableHeader().setBackground(new Color(242, 247, 251));
            table.getTableHeader().setPreferredSize(new Dimension(0, 40));
            table.getTableHeader().setReorderingAllowed(false);
            table.setDefaultRenderer(Object.class, new TableContentRenderer());
            return table;
        }

        private static JScrollPane tableScroll(JTable table) {
            JScrollPane scroll = new JScrollPane(table);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getViewport().setBackground(Color.WHITE);
            return scroll;
        }

        private static JLabel createStatusBadge(String status) {
            return new RoundedStatusLabel(status);
        }

        private static Color statusColor(String status) {
            if ("Disetujui".equals(status)) {
                return APPROVED;
            }
            if ("Ditolak".equals(status)) {
                return REJECTED;
            }
            return WAITING;
        }

        private static Color statusTint(String status) {
            if ("Disetujui".equals(status)) {
                return new Color(225, 245, 236);
            }
            if ("Ditolak".equals(status)) {
                return new Color(253, 232, 234);
            }
            return new Color(255, 242, 222);
        }

        private static List<ThesisApplication> loadApplications() throws IOException {
            try {
                return Database.loadThesisApplications().stream()
                        .map(Main::fromDatabaseApplication)
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            } catch (java.sql.SQLException ex) {
                throw new IOException("Data pengajuan MySQL tidak dapat dimuat: " + ex.getMessage(), ex);
            }
        }

        private static List<ThesisApplication> loadApplicationsFromFile() throws IOException {
            if (!Files.exists(THESIS_DATA_FILE)) {
                return new ArrayList<>();
            }
            try (DataInputStream input = new DataInputStream(Files.newInputStream(THESIS_DATA_FILE))) {
                int fileMagic = input.readInt();
                if (fileMagic != THESIS_FILE_MAGIC
                        && fileMagic != THESIS_FILE_MAGIC_V3
                        && fileMagic != THESIS_FILE_MAGIC_V2
                        && fileMagic != THESIS_FILE_MAGIC_V1) {
                    throw new IOException("Format data pendaftaran skripsi tidak dikenal.");
                }
                boolean hasV2Fields = fileMagic == THESIS_FILE_MAGIC_V2
                        || fileMagic == THESIS_FILE_MAGIC_V3
                        || fileMagic == THESIS_FILE_MAGIC;
                boolean hasV3Fields = fileMagic == THESIS_FILE_MAGIC_V3
                        || fileMagic == THESIS_FILE_MAGIC;
                boolean hasV4Fields = fileMagic == THESIS_FILE_MAGIC;
                int count = input.readInt();
                if (count < 0 || count > 100_000) {
                    throw new IOException("Jumlah pengajuan di dalam file tidak valid.");
                }
                List<ThesisApplication> loaded = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    String id = input.readUTF();
                    String nama = input.readUTF();
                    String nim = input.readUTF();
                    String prodi = input.readUTF();
                    String email = input.readUTF();
                    String semester = hasV2Fields ? input.readUTF() : "—";
                    String judul = input.readUTF();
                    String deskripsi = input.readUTF();
                    String bidang = hasV2Fields ? input.readUTF() : "Belum ditentukan";
                    String status = input.readUTF();
                    String catatan = input.readUTF();
                    String tanggal = input.readUTF();
                    if ("Menunggu".equals(status)) {
                        status = PENDING_STATUS;
                    }
                    if (!Arrays.asList(PENDING_STATUS, "Disetujui", "Ditolak").contains(status)) {
                        throw new IOException("Status pengajuan pada data tersimpan tidak valid.");
                    }
                    ThesisApplication application = new ThesisApplication(
                            id, nama, nim, prodi, email, semester, judul,
                            deskripsi, bidang, status, catatan, tanggal);
                    if (hasV3Fields && input.readBoolean()) {
                        double score = input.readDouble();
                        String comparedTitle = input.readUTF();
                        String comparedNim = input.readUTF();
                        if (!Double.isFinite(score) || score < 0 || score > 1
                                || comparedTitle.isBlank()) {
                            throw new IOException("Hasil kemiripan pada data tersimpan tidak valid.");
                        }
                        application.similarityScore = score;
                        application.similarityComparedTitle = comparedTitle;
                        application.similarityComparedNim = comparedNim;
                    }
                    if (hasV4Fields) {
                        int historyCount = input.readInt();
                        if (historyCount < 0 || historyCount > 10_000) {
                            throw new IOException("Jumlah riwayat status pada data tersimpan tidak valid.");
                        }
                        for (int historyIndex = 0; historyIndex < historyCount; historyIndex++) {
                            String historyApplicationId = input.readUTF();
                            String historyNim = input.readUTF();
                            String previousStatus = input.readUTF();
                            String newStatus = input.readUTF();
                            String changedAt = input.readUTF();
                            String note = input.readUTF();
                            if (!application.id.equals(historyApplicationId)
                                    || historyNim.isBlank()
                                    || previousStatus.equals(newStatus)
                                    || !Arrays.asList(
                                            PENDING_STATUS, "Disetujui", "Ditolak")
                                            .contains(previousStatus)
                                    || !Arrays.asList(
                                            PENDING_STATUS, "Disetujui", "Ditolak")
                                            .contains(newStatus)
                                    || changedAt.isBlank()) {
                                throw new IOException("Riwayat status pada data tersimpan tidak valid.");
                            }
                            application.statusHistory.add(new StatusHistory(
                                    historyApplicationId,
                                    historyNim,
                                    previousStatus,
                                    newStatus,
                                    changedAt,
                                    note));
                        }
                    }
                    loaded.add(application);
                }
                if (input.read() != -1) {
                    throw new IOException("Berkas data memiliki isi tambahan yang tidak valid.");
                }
                return loaded;
            } catch (EOFException ex) {
                throw new IOException("Berkas data pendaftaran tidak lengkap atau rusak.", ex);
            }
        }

        private static void saveApplications(List<ThesisApplication> items) throws IOException {
            try {
                Database.replaceThesisApplications(toDatabaseApplications(items));
            } catch (java.sql.SQLException ex) {
                throw new IOException("Data pengajuan MySQL tidak dapat disimpan: " + ex.getMessage(), ex);
            }
        }
    }
}