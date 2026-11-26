package pertemuan03;

import java.util.Scanner;

public class InputMahasiswaScanner {
    public static void main(String[] args) {

        Scanner sc = new Scanner(System.in);

        System.out.println("========================================");
        System.out.println("          INPUT DATA MAHASISWA");
        System.out.println("========================================");

        System.out.print("Nama          : ");
        String nama = sc.nextLine();

        System.out.print("NIM           : ");
        String nim = sc.nextLine();

        System.out.print("Prodi         : ");
        String prodi = sc.nextLine();

        System.out.print("Semester      : ");
        int semester = sc.nextInt();

        System.out.print("Nilai         : ");
        double nilai = sc.nextDouble();

        sc.nextLine(); // membersihkan enter

        System.out.print("Jenis Kelamin : ");
        String jenisKelamin = sc.nextLine();

        System.out.println();
        System.out.println("========================================");
        System.out.println("          DATA MAHASISWA");
        System.out.println("========================================");
        System.out.println("Nama          : " + nama);
        System.out.println("NIM           : " + nim);
        System.out.println("Prodi         : " + prodi);
        System.out.println("Semester      : " + semester);
        System.out.println("Nilai         : " + nilai);
        System.out.println("Jenis Kelamin : " + jenisKelamin);
        System.out.println("========================================");
        System.out.println("       Data berhasil ditampilkan!");
        System.out.println("========================================");

        sc.close();
    }
}