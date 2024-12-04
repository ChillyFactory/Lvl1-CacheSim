import java.util.Scanner;

public class CalculateAddress {
    public static void main(String[] args) {
        // Create a scanner to get input from the user
        Scanner scanner = new Scanner(System.in);
        String userInput;

        System.out.println("Enter a value to calculate ((inputValue) / 32) % 32.");
        System.out.println("Type 'q' to quit.");

        // Loop until the user enters 'q'
        while (true) {
            System.out.print("Enter a value (or 'q' to quit): ");
            userInput = scanner.nextLine(); // Read the user's input

            // Check if the user wants to quit
            if (userInput.equalsIgnoreCase("q")) {
                System.out.println("Exiting program. Goodbye!");
                break; // Exit the loop
            }

            try {
                // Convert the input to a number and perform the calculation
                int inputValue = Integer.parseInt(userInput, 16);
                int result = (inputValue / 32) % 32;
                System.out.println("The result of ((inputValue) / 32) % 32 is: " + result);
            } catch (NumberFormatException e) {
                // Handle invalid input
                System.out.println("Invalid input. Please enter a number or 'q' to quit.");
            }
        }

        // Close the scanner
        scanner.close();
    }
}
