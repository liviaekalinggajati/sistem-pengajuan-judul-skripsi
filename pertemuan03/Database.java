package pertemuan03;

import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Database {
    private static final int PASSWORD_ITERATIONS = 210_000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String DEFAULT_DATABASE_URL =
            "jdbc:mysql://localhost:3306/pendaftaran_skripsi"
                    + "?serverTimezone=UTC&allowPublicKeyRetrieval=true&sslMode=DISABLED";

    enum StudentRegistrationResult {
        REGISTERED,
        ACCOUNT_EXISTS
    }

    record DummyStudentProfile(String nim, String name, String program, int semester) {
    }

    record StudentProfile(String nim, String name, String program, int semester, String email) {
        boolean isComplete() {
            return nim != null && !nim.isBlank()
                    && name != null && !name.isBlank()
                    && program != null && !program.isBlank()
                    && semester > 0
                    && email != null && !email.isBlank();
        }
    }

    record StudentRecord(String name, String nim, String program, int semester, Double score) {
    }

    record StatusHistoryRecord(
            String applicationId, String nim, String previousStatus, String newStatus,
            String changedAt, String note) {
    }

    record ThesisApplicationRecord(
            String id, String name, String nim, String program, String email, String semester,
            String title, String description, String field, String status, String note, String date,
            Double similarityScore, String similarityComparedTitle, String similarityComparedNim,
            List<StatusHistoryRecord> statusHistory) {
        ThesisApplicationRecord {
            statusHistory = List.copyOf(statusHistory);
        }
    }

    private Database() {
    }

    static boolean hasLegacyDataBeenImported() throws SQLException {
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM app_metadata WHERE `key` = ?")) {
            statement.setString(1, "legacy_files_imported_v1");
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    static void importLegacyData(List<StudentRecord> students, List<ThesisApplicationRecord> applications)
            throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement marker = connection.prepareStatement(
                        "SELECT 1 FROM app_metadata WHERE `key` = ? FOR UPDATE")) {
                    marker.setString(1, "legacy_files_imported_v1");
                    try (ResultSet result = marker.executeQuery()) {
                        if (result.next()) {
                            connection.rollback();
                            return;
                        }
                    }
                }

                try (PreparedStatement insertStudent = connection.prepareStatement(
                        "INSERT IGNORE INTO data_mahasiswa "
                                + "(nim, nama, program_studi, semester, nilai) VALUES (?, ?, ?, ?, ?)")) {
                    for (StudentRecord student : students) {
                        bindStudent(insertStudent, student);
                        insertStudent.addBatch();
                    }
                    insertStudent.executeBatch();
                }

                try (PreparedStatement insertApplication = connection.prepareStatement(
                        applicationInsertSql());
                        PreparedStatement insertHistory = connection.prepareStatement(historyInsertSql())) {
                    for (ThesisApplicationRecord application : applications) {
                        bindApplication(insertApplication, application);
                        if (insertApplication.executeUpdate() == 1) {
                            insertHistory(application, insertHistory);
                        }
                    }
                }

                try (PreparedStatement marker = connection.prepareStatement(
                        "INSERT INTO app_metadata (`key`, `value`) VALUES (?, ?)")) {
                    marker.setString(1, "legacy_files_imported_v1");
                    marker.setString(2, "1");
                    marker.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    static List<StudentRecord> loadStudentRecords() throws SQLException {
        List<StudentRecord> students = new ArrayList<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT nama, nim, program_studi, semester, nilai "
                                + "FROM data_mahasiswa ORDER BY nama, nim");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                double score = result.getDouble("nilai");
                boolean scoreIsNull = result.wasNull();
                students.add(new StudentRecord(
                        result.getString("nama"),
                        result.getString("nim"),
                        result.getString("program_studi"),
                        result.getInt("semester"),
                        scoreIsNull ? null : score));
            }
        }
        return students;
    }

    static void replaceStudentRecords(List<StudentRecord> students) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM data_mahasiswa");
                }
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO data_mahasiswa "
                                + "(nim, nama, program_studi, semester, nilai) VALUES (?, ?, ?, ?, ?)")) {
                    for (StudentRecord student : students) {
                        bindStudent(insert, student);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    static List<ThesisApplicationRecord> loadThesisApplications() throws SQLException {
        Map<String, List<StatusHistoryRecord>> histories = new HashMap<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT application_id, nim, previous_status, new_status, changed_at, note "
                                + "FROM riwayat_status_pengajuan ORDER BY history_id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                StatusHistoryRecord history = new StatusHistoryRecord(
                        result.getString("application_id"),
                        result.getString("nim"),
                        result.getString("previous_status"),
                        result.getString("new_status"),
                        result.getString("changed_at"),
                        result.getString("note"));
                histories.computeIfAbsent(history.applicationId(), ignored -> new ArrayList<>())
                        .add(history);
            }
        }

        List<ThesisApplicationRecord> applications = new ArrayList<>();
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, nama, nim, program_studi, email, semester, judul, deskripsi, bidang, "
                                + "tanggal, status, catatan, similarity_score, "
                                + "similarity_compared_title, similarity_compared_nim "
                                + "FROM pengajuan_skripsi ORDER BY tanggal");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                double similarityScore = result.getDouble("similarity_score");
                boolean similarityScoreIsNull = result.wasNull();
                applications.add(new ThesisApplicationRecord(
                        result.getString("id"),
                        result.getString("nama"),
                        result.getString("nim"),
                        result.getString("program_studi"),
                        result.getString("email"),
                        result.getString("semester"),
                        result.getString("judul"),
                        result.getString("deskripsi"),
                        result.getString("bidang"),
                        result.getString("status"),
                        result.getString("catatan"),
                        result.getString("tanggal"),
                        similarityScoreIsNull ? null : similarityScore,
                        result.getString("similarity_compared_title"),
                        result.getString("similarity_compared_nim"),
                        histories.getOrDefault(result.getString("id"), List.of())));
            }
        }
        return applications;
    }

    private static void bindStudent(PreparedStatement insert, StudentRecord student) throws SQLException {
        insert.setString(1, student.nim());
        insert.setString(2, student.name());
        insert.setString(3, student.program());
        insert.setInt(4, student.semester());
        if (student.score() == null || !Double.isFinite(student.score())) {
            insert.setNull(5, java.sql.Types.DOUBLE);
        } else {
            insert.setDouble(5, student.score());
        }
    }

    private static String applicationInsertSql() {
        return "INSERT INTO pengajuan_skripsi "
                + "(id, nama, nim, program_studi, email, semester, judul, deskripsi, bidang, tanggal, "
                + "status, catatan, similarity_score, similarity_compared_title, similarity_compared_nim) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    }

    private static void bindApplication(
            PreparedStatement insert, ThesisApplicationRecord application) throws SQLException {
        insert.setString(1, application.id());
        insert.setString(2, application.name());
        insert.setString(3, application.nim());
        insert.setString(4, application.program());
        insert.setString(5, application.email());
        insert.setString(6, application.semester());
        insert.setString(7, application.title());
        insert.setString(8, application.description());
        insert.setString(9, application.field());
        insert.setString(10, application.date());
        insert.setString(11, application.status());
        insert.setString(12, application.note());
        if (application.similarityScore() == null) {
            insert.setNull(13, java.sql.Types.DOUBLE);
        } else {
            insert.setDouble(13, application.similarityScore());
        }
        insert.setString(14, application.similarityComparedTitle());
        insert.setString(15, application.similarityComparedNim());
    }

    private static String historyInsertSql() {
        return "INSERT INTO riwayat_status_pengajuan "
                + "(application_id, nim, previous_status, new_status, changed_at, note) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
    }

    private static void insertHistory(
            ThesisApplicationRecord application, PreparedStatement insert) throws SQLException {
        for (StatusHistoryRecord history : application.statusHistory()) {
            insert.setString(1, history.applicationId());
            insert.setString(2, history.nim());
            insert.setString(3, history.previousStatus());
            insert.setString(4, history.newStatus());
            insert.setString(5, history.changedAt());
            insert.setString(6, history.note());
            insert.executeUpdate();
        }
    }

    static void replaceThesisApplications(List<ThesisApplicationRecord> applications) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM riwayat_status_pengajuan");
                    statement.executeUpdate("DELETE FROM pengajuan_skripsi");
                }
                try (PreparedStatement insertApplication = connection.prepareStatement(
                        applicationInsertSql());
                        PreparedStatement insertHistory = connection.prepareStatement(historyInsertSql())) {
                    for (ThesisApplicationRecord application : applications) {
                        bindApplication(insertApplication, application);
                        insertApplication.executeUpdate();
                        insertHistory(application, insertHistory);
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    static boolean authenticateAdmin(String username, char[] password) throws SQLException {
        if (username == null || username.isBlank() || password == null || password.length == 0) {
            return false;
        }
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT password_hash FROM admin WHERE username = ?")) {
            statement.setString(1, username.trim());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && verifyPassword(password, result.getString("password_hash"));
            }
        }
    }

    static boolean authenticateStudent(String nim, char[] password) throws SQLException {
        if (nim == null || nim.isBlank() || password == null || password.length == 0) {
            return false;
        }
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT password_hash FROM mahasiswa WHERE nim = ?")) {
            statement.setString(1, nim.trim());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && verifyPassword(password, result.getString("password_hash"));
            }
        }
    }

    static String findStudentName(String nim) throws SQLException {
        if (nim == null || nim.isBlank()) {
            return null;
        }
        try (Connection connection = openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT nama FROM mahasiswa WHERE nim = ?")) {
            statement.setString(1, nim.trim());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString("nama") : null;
            }
        }
    }

    static StudentProfile findStudentProfile(String nim) throws SQLException {
        if (nim == null || nim.isBlank()) {
            return null;
        }
        try (Connection connection = openConnection()) {
            ensureStudentEmailColumn(connection);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT nim, nama, program_studi, semester, email "
                            + "FROM mahasiswa WHERE nim = ?")) {
                statement.setString(1, nim.trim());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return null;
                    }
                    return new StudentProfile(
                            result.getString("nim"),
                            result.getString("nama"),
                            result.getString("program_studi"),
                            result.getInt("semester"),
                            result.getString("email"));
                }
            }
        }
    }

    static StudentProfile updateStudentProfile(
            String nim, String name, String program, int semester, String email) throws SQLException {
        if (nim == null || nim.isBlank() || semester < 1) {
            throw new IllegalArgumentException("NIM dan semester profil harus valid.");
        }
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureStudentEmailColumn(connection);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE mahasiswa SET nama = ?, program_studi = ?, semester = ?, email = ? "
                                + "WHERE nim = ?")) {
                    statement.setString(1, name == null ? "" : name.trim());
                    statement.setString(2, program == null ? "" : program.trim());
                    statement.setInt(3, semester);
                    statement.setString(4, email == null ? "" : email.trim());
                    statement.setString(5, nim.trim());
                    if (statement.executeUpdate() != 1) {
                        throw new SQLException("Akun mahasiswa dengan NIM tersebut tidak ditemukan.");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        }
        return findStudentProfile(nim);
    }

    static StudentRegistrationResult registerStudent(
            String nim, String name, String program, int semester, String email, char[] password)
            throws SQLException {
        if (nim == null || nim.isBlank() || name == null || name.isBlank()
                || program == null || program.isBlank() || semester < 1
                || email == null || email.isBlank() || password == null || password.length < 8) {
            throw new IllegalArgumentException("Data pendaftaran akun tidak valid.");
        }
        String encodedPassword = hashPassword(password);
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureStudentEmailColumn(connection);
                try (PreparedStatement existing = connection.prepareStatement(
                        "SELECT 1 FROM mahasiswa WHERE nim = ?")) {
                    existing.setString(1, nim.trim());
                    try (ResultSet result = existing.executeQuery()) {
                        if (result.next()) {
                            connection.rollback();
                            return StudentRegistrationResult.ACCOUNT_EXISTS;
                        }
                    }
                }

                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO mahasiswa "
                                + "(nim, nama, program_studi, semester, password_hash, email) "
                                + "VALUES (?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, nim.trim());
                    insert.setString(2, name.trim());
                    insert.setString(3, program.trim());
                    insert.setInt(4, semester);
                    insert.setString(5, encodedPassword);
                    insert.setString(6, email.trim());
                    insert.executeUpdate();
                }
                connection.commit();
                return StudentRegistrationResult.REGISTERED;
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    static int ensureDummyStudentAccounts(List<DummyStudentProfile> students) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                int accountsCreated = 0;
                for (DummyStudentProfile student : students) {
                    if (student.nim() == null || !student.nim().matches("DEMO-SI-\\d{3}")
                            || student.name() == null || student.name().isBlank()
                            || student.program() == null || student.program().isBlank()
                            || student.semester() < 1) {
                        throw new IllegalArgumentException("Data akun mahasiswa dummy tidak valid.");
                    }

                    try (PreparedStatement existing = connection.prepareStatement(
                            "SELECT 1 FROM mahasiswa WHERE nim = ?")) {
                        existing.setString(1, student.nim());
                        try (ResultSet result = existing.executeQuery()) {
                            if (result.next()) {
                                continue;
                            }
                        }
                    }

                    char[] temporaryPassword = "Mahasiswa123".toCharArray();
                    String passwordHash;
                    try {
                        passwordHash = hashPassword(temporaryPassword);
                    } finally {
                        java.util.Arrays.fill(temporaryPassword, '\0');
                    }

                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT IGNORE INTO mahasiswa "
                                    + "(nim, nama, program_studi, semester, password_hash) "
                                    + "VALUES (?, ?, ?, ?, ?)")) {
                        insert.setString(1, student.nim());
                        insert.setString(2, student.name());
                        insert.setString(3, student.program());
                        insert.setInt(4, student.semester());
                        insert.setString(5, passwordHash);
                        accountsCreated += insert.executeUpdate();
                    }
                }
                connection.commit();
                return accountsCreated;
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        }
    }

    private static Connection openConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new SQLException(
                    "Driver MySQL JDBC tidak ditemukan di classpath. Tambahkan MySQL Connector/J.", ex);
        }
        String url = environmentOrDefault("DB_URL", DEFAULT_DATABASE_URL);
        String username = environmentOrDefault("DB_USER", "root");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        return DriverManager.getConnection(url, username, password);
    }

    private static void ensureStudentEmailColumn(Connection connection) throws SQLException {
        boolean hasEmail;
        try (ResultSet columns = connection.getMetaData().getColumns(
                connection.getCatalog(), null, "mahasiswa", "email")) {
            hasEmail = columns.next();
        }
        if (!hasEmail) {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(
                        "ALTER TABLE mahasiswa ADD COLUMN email VARCHAR(255) NOT NULL DEFAULT ''");
            }
        }
    }

    private static String environmentOrDefault(String name, String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String hashPassword(char[] password) throws SQLException {
        byte[] salt = new byte[16];
        SECURE_RANDOM.nextBytes(salt);
        PBEKeySpec keySpec = new PBEKeySpec(password, salt, PASSWORD_ITERATIONS, 256);
        try {
            byte[] hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(keySpec)
                    .getEncoded();
            return "pbkdf2_sha256$" + PASSWORD_ITERATIONS + "$"
                    + HexFormat.of().formatHex(salt) + "$" + HexFormat.of().formatHex(hash);
        } catch (GeneralSecurityException ex) {
            throw new SQLException("Password tidak dapat diamankan.", ex);
        } finally {
            keySpec.clearPassword();
        }
    }

    private static boolean verifyPassword(char[] password, String encodedPassword) throws SQLException {
        String[] parts = encodedPassword == null ? new String[0] : encodedPassword.split("\\$", -1);
        if (parts.length != 4 || !"pbkdf2_sha256".equals(parts[0])) {
            throw new SQLException("Format hash password pada database tidak valid.");
        }

        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = HexFormat.of().parseHex(parts[2]);
            byte[] expectedHash = HexFormat.of().parseHex(parts[3]);
            if (iterations < 10_000 || iterations > 1_000_000
                    || salt.length < 16 || expectedHash.length != 32) {
                throw new SQLException("Parameter hash password pada database tidak valid.");
            }

            PBEKeySpec keySpec = new PBEKeySpec(password, salt, iterations, expectedHash.length * 8);
            try {
                byte[] actualHash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(keySpec)
                        .getEncoded();
                return MessageDigest.isEqual(expectedHash, actualHash);
            } finally {
                keySpec.clearPassword();
            }
        } catch (IllegalArgumentException ex) {
            throw new SQLException("Format hash password pada database tidak valid.", ex);
        } catch (GeneralSecurityException ex) {
            throw new SQLException("Hash password tidak dapat diverifikasi.", ex);
        }
    }
}
