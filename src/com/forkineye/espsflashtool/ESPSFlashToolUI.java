/*
 * Copyright 2016 Shelby Merrick
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.forkineye.espsflashtool;
import com.fazecast.jSerialComm.*;
import com.google.gson.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import static javax.swing.JOptionPane.showMessageDialog;


/**
 *
 * @author sporadic
 */

// Device Modes
class ESPSDeviceMode {
    private final String name;          // Name of the mode for toString();
    private final String description;   // Mode description
    private final String file;          // Name of firmware binary

    public ESPSDeviceMode(String name, String description, String file) {
        this.name = name;
        this.description = description;
        this.file = file;
    }
    
    public String getFile() {
        return file;
    }
    
    @Override
    public String toString() {
        return name;
    }
}

// Serial Ports
class ESPSSerialPort {
    private final SerialPort port;      // Hardwire ID or path to port;

    public ESPSSerialPort(SerialPort port) {
        this.port = port;
    }
    
    public SerialPort getPort() {
        return port;
    }

    @Override
    public String toString() {
        return (port.getSystemPortName() + " - " + port.getDescriptivePortName());
    }    
}

// ESPixelStick JSON Config
class ESPSConfig {
    class Network {
        String  ssid;
        String  passphrase;
        String  hostname;
        int[]   ip = new int[4];
        int[]   netmask = new int[4];
        int[]   gateway = new int[4];
        boolean dhcp;           // Use DHCP
        boolean ap_fallback;    // Fallback to AP if fail to associate
    }

    class Device {
        String  id;             // Device ID
    }

    class E131 {
        int     universe;       // Universe to listen for
        int     channel_start;  // Channel to start listening at - 1 based
        int     channel_count;  // Number of channels
        boolean multicast;      // Enable multicast listener
    }

    class Pixel {
        int     pixel_type;     // Pixel type
        int     pixel_color;    // Pixel color order
        boolean gamma;          // Use gamma map?
    }
    
    class Serial {
        int serial_type;    // Serial type
        int baudrate;       // Baudrate
    }
    
    Network network;
    Device device;
    E131 e131;
    Pixel pixel;
    Serial serial;
}

// Device JSON Config
class FTDevice {
    String name;
    Esptool esptool;
    Mkspiffs mkspiffs;
    
    class Esptool {
        String reset;
        String baudrate;
        String spiffsloc;
    }
    
    class Mkspiffs {
        String page;
        String block;
        String size;
    }
}

// ESPSFlashTool JSON Config
class FTConfig {
    ArrayList<ESPSDeviceMode> modes;
    ArrayList<FTDevice> devices;
}

public class ESPSFlashToolUI extends javax.swing.JFrame {
    private final DefaultComboBoxModel<ESPSDeviceMode> modelMode = new DefaultComboBoxModel<>();
    private final DefaultComboBoxModel<ESPSSerialPort> modelPort = new DefaultComboBoxModel<>();
    private final String fwPath = "firmware/";          // Path for firmware bins
    private final String execPath = "bin/";             // Path for executables
    private final String spiffsPath = "spiffs/";        // Path for SPIFFS
    private final String spiffsBin = "spiffs.bin";      // SPIFFS Image
    private final String configJson = "config.json";    // ESPixelStick config.json
    private FlashTask ftask;                            // SwingWorker task to build and flash
    
    private String esptool;     // esptool binary to use with path
    private String mkspiffs;    // mkspiffs binary to use with path
    
    private ESPSConfig config;
    private FTConfig ftconfig;
    private FTDevice device;
    private ESPSSerialPort port;
    private ESPSDeviceMode mode;
    
    /**
     * Creates new form ESPSFlashToolUI
     */
    public ESPSFlashToolUI() {
        // Netbeans init routine
        initComponents();
        setLocationRelativeTo(null);
        
        // Detect OS and set binary paths
        if (!detectOS())
            showMessageDialog(null, "Failed to detect OS",
                    "OS Detection Failure", JOptionPane.ERROR_MESSAGE);            
        
        // Read FT Config
        Gson gson = new Gson();
        InputStream in = getClass().getResourceAsStream("espsflashtool.json");
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        ftconfig = gson.fromJson(reader, FTConfig.class);

        // Verify and Populate modes
        for (ESPSDeviceMode mode : ftconfig.modes) {
            if (new File(fwPath + mode.getFile()).isFile()) {
                modelMode.addElement(mode);
            } else {
                showMessageDialog(null, "Firmware not found for mode " + mode.toString(), 
                        "Bad mode configuration", JOptionPane.ERROR_MESSAGE);
            }
        }
               
        // Populate serial ports
        for (SerialPort port : SerialPort.getCommPorts())
            modelPort.addElement(new ESPSSerialPort(port));
        
        // Deserialize config.json
        try {
            config = gson.fromJson(
                    new FileReader(spiffsPath + configJson), ESPSConfig.class);
        } catch (FileNotFoundException ex) {
            showMessageDialog(null, "Unable to find ESPixelStick Configuration file", 
                    "Failed deserialize", JOptionPane.ERROR_MESSAGE);
        }

        // Populate config
        txtSSID.setText(config.network.ssid);
        txtPassphrase.setText(config.network.passphrase);
        txtDevID.setText(config.device.id);
        
        // Set default device
        device = ftconfig.devices.get(0);
    }

    private boolean serializeConfig() {
        boolean retval = true;
        config.network.ssid = txtSSID.getText();
        config.network.passphrase = txtPassphrase.getText();

        try (Writer fw = new FileWriter(spiffsPath + configJson)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(config, fw);
        } catch (IOException ex) {
            showMessageDialog(null, "Failed to save " + configJson, 
                    "Failed serialize", JOptionPane.ERROR_MESSAGE);
            retval = false;
        }
        
        return retval;
    }
    
    private boolean detectOS() {
        boolean retval = true;
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();
        System.out.println("OS: " + os + " / " + arch);
        
        if (os.contains("win")) {
            System.out.println("Detected Windows");
            esptool = execPath + "win/esptool.exe";
            mkspiffs = execPath + "win/mkspiffs.exe";
        } else if (os.contains("mac")) {
            System.out.println("Detected Mac");
            esptool = execPath + "osx/esptool";
            mkspiffs = execPath + "osx/mkspiffs";
        } else if (os.contains("linux") && arch.contains("32")) {
            esptool = execPath + "linux32/esptool";
            mkspiffs = execPath + "linux32/mkspiffs";
        } else if (os.contains("linux") && arch.contains("64")) {
            esptool = execPath + "linux64/esptool";
            mkspiffs = execPath + "linux64/mkspiffs";
        } else {
            retval = false;
        }
        
        return retval;
    }
    
    private List<String> cmdEsptool() {
        List<String> list = new ArrayList<>();
        
        list.add(esptool);
        list.add("-cd");
        list.add(device.esptool.reset);
        list.add("-cb");
        list.add(device.esptool.baudrate);
        list.add("-cp");
        list.add(port.getPort().getSystemPortName());
        list.add("-ca");
        list.add("0x000000");
        list.add("-cf");
        list.add(fwPath + mode.getFile());
        list.add("-ca");
        list.add(device.esptool.spiffsloc);
        list.add(fwPath + spiffsBin);
        
        return list;
    }
    
    private List<String> cmdMkspiffs() {
        List<String> list = new ArrayList<>();
        
        list.add(mkspiffs);
        list.add("-c");
        list.add(spiffsPath);
        list.add("-p");
        list.add(device.mkspiffs.page);
        list.add("-b");
        list.add(device.mkspiffs.block);
        list.add("-s");
        list.add(device.mkspiffs.size);
        list.add(fwPath + spiffsBin);
        
        return list;        
    }
    
    private class FlashTask extends SwingWorker<Integer, String> {
        private int state;
        private int status;

        public FlashTask() {}
        
        private int exec(List<String> command) {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String s;
                BufferedReader stdout = new BufferedReader(
                        new InputStreamReader(p.getInputStream()));
                
                while ((s = stdout.readLine()) != null && !isCancelled())
                    publish(s);

                if (!isCancelled())
                    state = p.waitFor();

                p.getInputStream().close();
                p.getOutputStream().close();
                p.getErrorStream().close();
                p.destroy();
                return p.exitValue();
            } catch (IOException | InterruptedException ex) {
                ex.printStackTrace(System.err);
                return -1;
            }
        }
        
        @Override
        protected Integer doInBackground() {
            String command = "";
            
            // Build SPIFFS
            txtOutput.setText(null);
            publish("-= Building SPIFFS Image =-");
            for (String opt : cmdMkspiffs())
                command = (command + " " + opt);
            publish(command);
            status = exec(cmdMkspiffs());
            if (status != 0) {
                showMessageDialog(null, "Failed to make SPIFFS Image",
                        "Failed mkspiffs", JOptionPane.ERROR_MESSAGE);
            } else {
                // Flash the images
                publish("\n-= Programming ESP8266 =-");
                command = "";
                for (String opt : cmdEsptool())
                    command = (command + " " + opt);
                publish(command);
                status = exec(cmdEsptool());
                if (status != 0) {
                    showMessageDialog(null, "Failed to program the ESP8266.\n" +
                            "Verify your device is properly connected and in programming mode.",
                            "Failed esptool", JOptionPane.ERROR_MESSAGE);
                }
            }
                    
            return state;
        }
        
        @Override
        protected void process(java.util.List<String> messages) {
            for (String message : messages)
                txtOutput.append(message + "\n");
        }

        @Override
        protected void done() {
            if (status == 0)
                txtOutput.append("\n-= Programming Complete =-");
            else
                txtOutput.append("\n*** PROGRAMMING FAILED ***");
        }
    }
    
    /**
     * This method is called from within the constructor to initialize the form.
     * WARNING: Do NOT modify this code. The content of this method is always
     * regenerated by the Form Editor.
     */
    @SuppressWarnings("unchecked")
    // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
    private void initComponents() {

        txtPassphrase = new javax.swing.JTextField();
        cboxMode = new javax.swing.JComboBox<>();
        txtDevID = new javax.swing.JTextField();
        jLabel5 = new javax.swing.JLabel();
        jLabel4 = new javax.swing.JLabel();
        cboxPort = new javax.swing.JComboBox<>();
        jLabel3 = new javax.swing.JLabel();
        txtSSID = new javax.swing.JTextField();
        jLabel2 = new javax.swing.JLabel();
        jLabel1 = new javax.swing.JLabel();
        btnFlash = new javax.swing.JButton();
        jScrollPane1 = new javax.swing.JScrollPane();
        txtOutput = new javax.swing.JTextArea();

        setDefaultCloseOperation(javax.swing.WindowConstants.EXIT_ON_CLOSE);
        setTitle("ESPixelStick Flash Tool");

        txtPassphrase.setToolTipText("Enter your AP Passphrase");

        cboxMode.setModel(modelMode);
        cboxMode.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cboxModeActionPerformed(evt);
            }
        });

        txtDevID.setToolTipText("Plain text name to help you identify this device");

        jLabel5.setText("Device ID");

        jLabel4.setText("Serial Port");

        cboxPort.setModel(modelPort);
        cboxPort.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                cboxPortActionPerformed(evt);
            }
        });

        jLabel3.setText("Device Mode");

        txtSSID.setToolTipText("Enter your AP SSID");

        jLabel2.setText("Passphrase");

        jLabel1.setText("SSID");

        btnFlash.setFont(new java.awt.Font("Tahoma", 0, 18)); // NOI18N
        btnFlash.setIcon(new javax.swing.ImageIcon(getClass().getResource("/com/forkineye/espsflashtool/upload.png"))); // NOI18N
        btnFlash.setText("Upload");
        btnFlash.setToolTipText("Program your ESPixelStick with current settings");
        btnFlash.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent evt) {
                btnFlashActionPerformed(evt);
            }
        });

        txtOutput.setEditable(false);
        txtOutput.setColumns(20);
        txtOutput.setRows(5);
        jScrollPane1.setViewportView(txtOutput);

        javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
        getContentPane().setLayout(layout);
        layout.setHorizontalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                    .addComponent(jScrollPane1)
                    .addGroup(layout.createSequentialGroup()
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(jLabel3)
                            .addComponent(jLabel1, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel2, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel5, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(jLabel4, javax.swing.GroupLayout.Alignment.TRAILING))
                        .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                        .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(txtPassphrase)
                            .addComponent(txtSSID, javax.swing.GroupLayout.Alignment.TRAILING)
                            .addComponent(cboxPort, 0, 418, Short.MAX_VALUE)
                            .addComponent(cboxMode, javax.swing.GroupLayout.Alignment.TRAILING, 0, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                            .addComponent(txtDevID, javax.swing.GroupLayout.Alignment.TRAILING)))
                    .addComponent(btnFlash, javax.swing.GroupLayout.Alignment.TRAILING, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE))
                .addContainerGap())
        );
        layout.setVerticalGroup(
            layout.createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(layout.createSequentialGroup()
                .addContainerGap()
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel1)
                    .addComponent(txtSSID, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel2)
                    .addComponent(txtPassphrase, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel5)
                    .addComponent(txtDevID, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(jLabel3)
                    .addComponent(cboxMode, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addGroup(layout.createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                    .addComponent(cboxPort, javax.swing.GroupLayout.PREFERRED_SIZE, javax.swing.GroupLayout.DEFAULT_SIZE, javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addComponent(jLabel4))
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(btnFlash)
                .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                .addComponent(jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 212, Short.MAX_VALUE)
                .addContainerGap())
        );

        pack();
    }// </editor-fold>//GEN-END:initComponents

    private void cboxPortActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cboxPortActionPerformed
        port = cboxPort.getItemAt(cboxPort.getSelectedIndex());
    }//GEN-LAST:event_cboxPortActionPerformed

    private void cboxModeActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_cboxModeActionPerformed
        mode = cboxMode.getItemAt(cboxMode.getSelectedIndex());
    }//GEN-LAST:event_cboxModeActionPerformed

    private void btnFlashActionPerformed(java.awt.event.ActionEvent evt) {//GEN-FIRST:event_btnFlashActionPerformed
        if (serializeConfig()) {
            ftask = new FlashTask();
            ftask.execute();
        }
    }//GEN-LAST:event_btnFlashActionPerformed

    /**
     * @param args the command line arguments
     */
    public static void main(String args[]) {
        /* Set the Nimbus look and feel */
        //<editor-fold defaultstate="collapsed" desc=" Look and feel setting code (optional) ">
        /* If Nimbus (introduced in Java SE 6) is not available, stay with the default look and feel.
         * For details see http://download.oracle.com/javase/tutorial/uiswing/lookandfeel/plaf.html 
         */
        try {
            for (javax.swing.UIManager.LookAndFeelInfo info : javax.swing.UIManager.getInstalledLookAndFeels()) {
                if ("Nimbus".equals(info.getName())) {
                    javax.swing.UIManager.setLookAndFeel(info.getClassName());
                    break;
                }
            }
        } catch (ClassNotFoundException ex) {
            java.util.logging.Logger.getLogger(ESPSFlashToolUI.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (InstantiationException ex) {
            java.util.logging.Logger.getLogger(ESPSFlashToolUI.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (IllegalAccessException ex) {
            java.util.logging.Logger.getLogger(ESPSFlashToolUI.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        } catch (javax.swing.UnsupportedLookAndFeelException ex) {
            java.util.logging.Logger.getLogger(ESPSFlashToolUI.class.getName()).log(java.util.logging.Level.SEVERE, null, ex);
        }
        //</editor-fold>

        /* Create and display the form */
        java.awt.EventQueue.invokeLater(new Runnable() {
            public void run() {
                new ESPSFlashToolUI().setVisible(true);
            }
        });
    }

    // Variables declaration - do not modify//GEN-BEGIN:variables
    private javax.swing.JButton btnFlash;
    private javax.swing.JComboBox<ESPSDeviceMode> cboxMode;
    private javax.swing.JComboBox<ESPSSerialPort> cboxPort;
    private javax.swing.JLabel jLabel1;
    private javax.swing.JLabel jLabel2;
    private javax.swing.JLabel jLabel3;
    private javax.swing.JLabel jLabel4;
    private javax.swing.JLabel jLabel5;
    private javax.swing.JScrollPane jScrollPane1;
    private javax.swing.JTextField txtDevID;
    private javax.swing.JTextArea txtOutput;
    private javax.swing.JTextField txtPassphrase;
    private javax.swing.JTextField txtSSID;
    // End of variables declaration//GEN-END:variables
}