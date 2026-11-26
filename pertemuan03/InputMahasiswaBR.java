package pertemuan03;

import java.io.BufferedReader;
import java.io.InputStreamReader; 
import java.io.IOException;

public class InputMahasiswaBR {
    public static void main(String[] args) throws IOException {

        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== INPUT DATA MAHASISWA ===");
        System.out.println();

        System.out.print("Nama          : ");
        String nama = br.readLine();

        System.out.print("NIM           : ");
        String nim = br.readLine();

        System.out.print("Prodi         : ");
        String prodi = br.readLine();

        System.out.print("Semester      : ");
        int semester = Integer.parseInt(br.readLine());

        System.out.print("Jenis Kelamin : ");
        String jenisKelamin = br.readLine();

        System.out.print("Nilai         : ");
        double nilai = Double.parseDouble(br.readLine());

    
        System.out.println();
        System.out.println("+--------------------------------------+");
        System.out.println("|           DATA MAHASISWA             |");
        System.out.println("+--------------------------------------+");
        System.out.println("| Nama          : " + nama);              
        System.out.println("| NIM           : " + nim);               
        System.out.println("| Prodi         : " + prodi);             
        System.out.println("| Semester      : " + semester);          
        System.out.println("| Jenis Kelamin : " + jenisKelamin);      
        System.out.println("| Nilai         : " + nilai);             
        System.out.println("+--------------------------------------+");
        System.out.println("|             TERIMA KASIH             |");
        System.out.println("+--------------------------------------+");
    }
}