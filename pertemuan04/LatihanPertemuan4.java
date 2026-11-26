package pertemuan04;

import java.util.Scanner;

public class LatihanPertemuan4 {

    public static void main(String[] args) {

        Scanner sc = new Scanner(System.in);

        System.out.println("==================================");
        System.out.println("       DATA NILAI MAHASISWA       ");
        System.out.println("==================================");

        System.out.print("Masukkan nama  : ");
        String nama = sc.nextLine();

        System.out.print("Masukkan NIM   : ");
        String nim = sc.nextLine();

        System.out.print("Masukkan nilai : ");
        double nilai = sc.nextDouble();

        String grade;

        if (nilai >= 80) {
            grade = "A";
        } else if (nilai >= 70) {
            grade = "B";
        } else if (nilai >= 60) {
            grade = "C";
        } else {
            grade = "D";
        }

        for (int i = 1; i <= 3; i++) {

            System.out.println();
            System.out.println("╔════════════════════════════════════╗");
            System.out.println("║              MENU                  ║");
            System.out.println("╠════════════════════════════════════╣");
            System.out.println("║  1. Data Mahasiswa                 ║");
            System.out.println("║  2. Nilai                          ║");
            System.out.println("║  3. Keluar                         ║");
            System.out.println("╚════════════════════════════════════╝");

            System.out.print("Pilih menu [1-3] : ");
            int pilihan = sc.nextInt();

            switch (pilihan) {

                case 1:
                    System.out.println();
                    System.out.println("====== DATA MAHASISWA ======");
                    System.out.println("Nama : " + nama);
                    System.out.println("NIM  : " + nim);
                    break;

                case 2:
                    System.out.println();
                    System.out.println("========== NILAI ==========");
                    System.out.println("Nilai : " + nilai);
                    System.out.println("Grade : " + grade);

                    switch (grade) {

                        case "A":
                            System.out.println("Keterangan : Sangat Baik");
                            break;

                        case "B":
                            System.out.println("Keterangan : Baik");
                            break;

                        case "C":
                            System.out.println("Keterangan : Cukup");
                            break;

                        case "D":
                            System.out.println("Keterangan : Kurang");
                            break;
                    }
                    break;

                case 3:
                    System.out.println();
                    System.out.println("Program selesai.");
                    i = 10;
                    break;

                default:
                    System.out.println();
                    System.out.println("Pilihan tidak tersedia.");
            }
        }

        sc.close();
    }
}