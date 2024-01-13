import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileTransferApp {
    private static final int PORT = 6789;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("File Transfer App");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());

            JTextArea textArea = new JTextArea();
            textArea.setEditable(false);

            JPanel clientPanel = createClientPanel(textArea);
            JPanel serverPanel = createServerPanel(textArea);

            JTabbedPane tabbedPane = new JTabbedPane();
            tabbedPane.addTab("Client", clientPanel);
            tabbedPane.addTab("Server", serverPanel);

            frame.getContentPane().add(tabbedPane, BorderLayout.CENTER);
            frame.getContentPane().add(new JScrollPane(textArea), BorderLayout.SOUTH);

            frame.setSize(600, 400);
            frame.setVisible(true);
        });
    }

    private static JPanel createClientPanel(JTextArea textArea) {
        JPanel clientPanel = new JPanel();
        clientPanel.setLayout(new BorderLayout());

        JTextField textField = new JTextField();
        JButton browseButton = new JButton("Browse");
        JButton sendButton = new JButton("Send");

        JPanel clientControlPanel = new JPanel();
        clientControlPanel.add(new JLabel("Select files to send: "));
        clientControlPanel.add(browseButton);
        clientControlPanel.add(textField);
        clientControlPanel.add(sendButton);

        clientPanel.add(clientControlPanel, BorderLayout.NORTH);

        browseButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser fileChooser = new JFileChooser();
                fileChooser.setMultiSelectionEnabled(true);

                int option = fileChooser.showOpenDialog(clientPanel);
                if (option == JFileChooser.APPROVE_OPTION) {
                    File[] selectedFiles = fileChooser.getSelectedFiles();
                    StringBuilder filesList = new StringBuilder();
                    for (File file : selectedFiles) {
                        filesList.append(file.getAbsolutePath()).append(";");
                    }
                    textField.setText(filesList.toString());
                }
            }
        });

        sendButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String ipAddress = JOptionPane.showInputDialog(clientPanel, "Enter Server IP Address:");
                if (ipAddress != null && !ipAddress.isEmpty()) {
                    String[] filePaths = textField.getText().split(";");
                    for (String filePath : filePaths) {
                        if (!filePath.isEmpty()) {
                            sendFile(filePath, textArea, ipAddress);
                        }
                    }
                }
            }
        });

        return clientPanel;
    }

    private static void sendFile(String filePath, JTextArea textArea, String ipAddress) {
        SwingWorker<Void, Integer> worker = new SwingWorker<Void, Integer>() {
            @Override
            protected Void doInBackground() throws Exception {
                try {
                    Socket clientSocket = new Socket(ipAddress, PORT);

                    // Send the file path
                    ObjectOutputStream objectOutToServer = new ObjectOutputStream(
                            clientSocket.getOutputStream());
                    objectOutToServer.writeObject(filePath);

                    // Send the file in chunks with progress
                    FileInputStream fileInputStream = new FileInputStream(filePath);
                    DataOutputStream dataOutToServer = new DataOutputStream(clientSocket.getOutputStream());

                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long fileSize = fileInputStream.available();
                    long totalBytesRead = 0;

                    dataOutToServer.writeLong(fileSize); // Send total file size

                    while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                        dataOutToServer.write(buffer, 0, bytesRead);
                        totalBytesRead += bytesRead;

                        // Calculate progress and publish it
                        int progress = (int) ((double) totalBytesRead / fileSize * 100);
                        publish(progress);
                    }

                    // Close the streams
                    fileInputStream.close();
                    clientSocket.close();

                    textArea.append("Sent file: " + filePath + "\n");
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                return null;
            }

            @Override
            protected void process(java.util.List<Integer> chunks) {
                // Update the progress bar in the UI
                for (int progress : chunks) {
                    textArea.append("Progress on client: " + progress + "%\n");
                }
            }
        };

        worker.execute();
    }

    private static JPanel createServerPanel(JTextArea textArea) {
        JPanel serverPanel = new JPanel();
        serverPanel.setLayout(new BorderLayout());

        JButton receiveButton = new JButton("Receive");
        serverPanel.add(receiveButton, BorderLayout.NORTH);

        receiveButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                textArea.setText("Waiting for files...\n");
                startServer(textArea);
            }
        });

        return serverPanel;
    }

    private static void startServer(JTextArea textArea) {
        new Thread(() -> {
            ExecutorService executorService = Executors.newFixedThreadPool(5);
            try {
                ServerSocket welcomeSocket = new ServerSocket(PORT);

                while (true) {
                    Socket connectionSocket = welcomeSocket.accept();
                    System.out.println("Client Connected");

                    // Create a new thread for each client
                    executorService.submit(new ClientHandler(connectionSocket, textArea));
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            } finally {
                executorService.shutdown();
            }
        }).start();
    }

    private static class ClientHandler implements Runnable {
        private Socket connectionSocket;
        private JTextArea textArea;

        public ClientHandler(Socket connectionSocket, JTextArea textArea) {
            this.connectionSocket = connectionSocket;
            this.textArea = textArea;
        }

        @Override
        public void run() {
            try {
                // Receive the file path
                ObjectInputStream objectInFromClient = new ObjectInputStream(connectionSocket.getInputStream());
                String filePath = (String) objectInFromClient.readObject();
                System.out.println("File path received from client: " + filePath);

                // Receive the file
                DataInputStream dataInFromClient = new DataInputStream(connectionSocket.getInputStream());

                // Receive file in chunks with progress
                FileOutputStream fileOutputStream = new FileOutputStream(
                        "server_" + System.currentTimeMillis() + "_" + getFileName(filePath));
                byte[] buffer = new byte[4096];
                int bytesRead;
                long totalBytesRead = 0;
                long fileSize = dataInFromClient.readLong(); // Read total file size

                // Display progress bar for the file
                JProgressBar progressBar = new JProgressBar(0, 100);
                progressBar.setStringPainted(true);
                SwingUtilities.invokeLater(() -> {
                    textArea.append("Receiving file: " + filePath + "\n");
                    textArea.append("Progress on server: ");
                    textArea.setCaretPosition(textArea.getDocument().getLength());
                    textArea.add(progressBar);
                });

                while ((bytesRead = dataInFromClient.read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    // Calculate progress and update the progress bar
                    int progress = (int) ((double) totalBytesRead / fileSize * 100);
                    SwingUtilities.invokeLater(() -> progressBar.setValue(progress));
                }

                // Close the streams
                fileOutputStream.close();
                connectionSocket.close();

                textArea.append("Received file: " + filePath + "\n");
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        private String getFileName(String filePath) {
            // Extract file name from the path
            int lastIndex = filePath.lastIndexOf(File.separator);
            if (lastIndex != -1) {
                return filePath.substring(lastIndex + 1);
            } else {
                return filePath;
            }
        }
    }
}
