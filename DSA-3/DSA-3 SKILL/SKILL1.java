import java.util.Scanner;

public class DocumentSearch {

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);

        // Input number of documents
        System.out.print("Enter number of documents (1-10): ");
        int N = sc.nextInt();
        sc.nextLine();

        // Constraint check
        if (N < 1 || N > 10) {
            System.out.println("Number of documents must be between 1 and 10.");
            return;
        }

        String[] documents = new String[N];

        // Input document titles
        System.out.println("Enter document titles:");
        for (int i = 0; i < N; i++) {
            documents[i] = sc.nextLine();

            if (documents[i].length() > 100) {
                System.out.println("Document title exceeds 100 characters.");
                return;
            }
        }

        // Input search keyword
        System.out.print("Enter search keyword: ");
        String keyword = sc.nextLine();

        if (keyword.length() > 20) {
            System.out.println("Keyword exceeds 20 characters.");
            return;
        }

        // Case-insensitive search
        boolean found = false;

        System.out.println("\nMatching Documents:");
        for (int i = 0; i < N; i++) {
            if (documents[i].toLowerCase().contains(keyword.toLowerCase())) {
                System.out.println(documents[i]);
                found = true;
            }
        }

        // No match found
        if (!found) {
            System.out.println("No matching documents found.");
        }

        sc.close();
    }
}