package pertemuan03;

import java.awt.Image;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JOptionPane;
import javax.swing.UIManager;

public class InputMahasiswaJOption {

    public static void main(String[] args) {
        applySeaBlueTheme();

        ImageIcon logo;
        try {
            logo = loadLogo();
        } catch (IOException e) {
            JOptionPane.showMessageDialog(
                    null,
                    "Logo tidak dapat dimuat: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        String nama = JOptionPane.showInputDialog(
                null,
                "Masukkan Nama:",
                "Input Data Mahasiswa",
                JOptionPane.QUESTION_MESSAGE
        );

        String nim = JOptionPane.showInputDialog(
                null,
                "Masukkan NIM:",
                "Input Data Mahasiswa",
                JOptionPane.QUESTION_MESSAGE
        );

        String prodi = JOptionPane.showInputDialog(
                null,
                "Masukkan Prodi:",
                "Input Data Mahasiswa",
                JOptionPane.QUESTION_MESSAGE
        );

        String semesterInput = JOptionPane.showInputDialog(
                null,
                "Masukkan Semester:",
                "Input Data Mahasiswa",
                JOptionPane.QUESTION_MESSAGE
        );
        int semester;
        try {
            semester = Integer.parseInt(semesterInput);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(
                    null,
                    "Semester harus diisi dengan angka!",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        String jenisKelamin = JOptionPane.showInputDialog(
                null,
                "Masukkan Jenis Kelamin:",
                "Input Data Mahasiswa",
                JOptionPane.QUESTION_MESSAGE
        );

        String nilaiInput = JOptionPane.showInputDialog(
                null,
                "Masukkan Nilai:",
                "Input Data Mahasiswa",
                JOptionPane.QUESTION_MESSAGE
        );
        double nilai;
        try {
            nilai = Double.parseDouble(nilaiInput);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(
                    null,
                    "Nilai harus diisi dengan angka!",
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
            return;
        }

        String hasil =
                "<html>" +
                "<div style='font-family:Arial; width:300px; color:#315B68;'>" +
                "<h2 style='color:#568FA0; text-align:center;'>DATA MAHASISWA</h2>" +
                "<hr>" +
                "<b style='color:#568FA0;'>Nama</b> : " + nama + "<br>" +
                "<b style='color:#568FA0;'>NIM</b> : " + nim + "<br>" +
                "<b style='color:#568FA0;'>Prodi</b> : " + prodi + "<br>" +
                "<b style='color:#568FA0;'>Semester</b> : " + semester + "<br>" +
                "<b style='color:#568FA0;'>Jenis Kelamin</b> : " + jenisKelamin + "<br>" +
                "<b style='color:#568FA0;'>Nilai</b> : " + nilai +
                "<hr>" +
                "<p style='text-align:center; color:#568FA0;'>" +
                "Data berhasil ditampilkan" +
                "</p>" +
                "</div>" +
                "</html>";

        JOptionPane.showMessageDialog(
                null,
                hasil,
                "Data Mahasiswa",
                JOptionPane.INFORMATION_MESSAGE,
                logo
        );
    }

    private static void applySeaBlueTheme() {
        Color background = new Color(235, 246, 248);
        Color buttonBackground = new Color(211, 232, 237);
        Color foreground = new Color(49, 91, 104);

        UIManager.put("Panel.background", background);
        UIManager.put("OptionPane.background", background);
        UIManager.put("OptionPane.messageForeground", foreground);
        UIManager.put("Button.background", buttonBackground);
        UIManager.put("Button.foreground", foreground);
        UIManager.put("TextField.background", Color.WHITE);
        UIManager.put("TextField.foreground", foreground);
        UIManager.put("TextField.caretForeground", foreground);
    }

    private static ImageIcon loadLogo() throws IOException {
        String fileName = "logo-unhasy-baru.png";
        BufferedImage image = null;

        var logoUrl = InputMahasiswaJOption.class.getResource(fileName);
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
            throw new IOException("file " + fileName + " tidak ditemukan atau bukan gambar yang didukung");
        }

        Image scaledImage = image.getScaledInstance(96, 96, Image.SCALE_SMOOTH);
        return new ImageIcon(scaledImage);
    }
}