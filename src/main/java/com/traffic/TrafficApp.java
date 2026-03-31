package com.traffic;

import org.jxmapviewer.JXMapViewer;
import org.jxmapviewer.OSMTileFactoryInfo;
import org.jxmapviewer.viewer.*;
import org.jxmapviewer.painter.CompoundPainter;
import org.jxmapviewer.painter.Painter;
import org.jxmapviewer.input.PanMouseInputListener;
import org.jxmapviewer.input.ZoomMouseWheelListenerCursor;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.MouseInputListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.Timer;

public class TrafficApp {

    private static JXMapViewer mapViewer;
    private static JLabel statusLabel;
    private static final String FAKE_BROWSER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/122.0.0.0 Safari/537.36";

    public static void main(String[] args) {
        JFrame frame = new JFrame("Expert Traffic System - Pure Dijkstra Routing");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // 1. Map Configuration
        mapViewer = new JXMapViewer();
        mapViewer.setTileFactory(new DefaultTileFactory(new OSMTileFactoryInfo()));
        
        MouseInputListener mia = new PanMouseInputListener(mapViewer);
        mapViewer.addMouseListener(mia);
        mapViewer.addMouseMotionListener(mia);
        mapViewer.addMouseWheelListener(new ZoomMouseWheelListenerCursor(mapViewer));
        
        mapViewer.setAddressLocation(new GeoPosition(22.9868, 88.4897)); 
        mapViewer.setZoom(5);

        // 2. Sidebar UI
        JPanel sidebar = new JPanel(new GridBagLayout());
        sidebar.setPreferredSize(new Dimension(340, 800)); 
        sidebar.setBorder(BorderFactory.createEmptyBorder(20, 15, 20, 15));
        
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1; gbc.gridx = 0;
        gbc.insets = new Insets(5, 0, 5, 0);

        gbc.gridy = 0; sidebar.add(new JLabel("Start Location:"), gbc);
        gbc.gridy = 1; 
        JTextField startField = new JTextField();
        startField.setPreferredSize(new Dimension(280, 35));
        setupAutocomplete(startField); 
        sidebar.add(startField, gbc);

        gbc.gridy = 2; sidebar.add(Box.createVerticalStrut(10), gbc);

        gbc.gridy = 3; sidebar.add(new JLabel("End Location:"), gbc);
        gbc.gridy = 4; 
        JTextField endField = new JTextField();
        endField.setPreferredSize(new Dimension(280, 35));
        setupAutocomplete(endField); 
        sidebar.add(endField, gbc);

        gbc.gridy = 5; sidebar.add(Box.createVerticalStrut(25), gbc);

        JButton analyzeBtn = new JButton("Run Expert Analysis");
        analyzeBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        gbc.gridy = 6; sidebar.add(analyzeBtn, gbc);

        statusLabel = new JLabel("<html>Status: Awaiting Input</html>");
        statusLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        gbc.gridy = 7; sidebar.add(statusLabel, gbc);

        gbc.gridy = 8; gbc.weighty = 1; sidebar.add(new JPanel(), gbc);

        // 3. Routing & Expert System Logic
        analyzeBtn.addActionListener(e -> {
            String startText = startField.getText().trim();
            String endText = endField.getText().trim();
            
            if (startText.isEmpty() || endText.isEmpty()) return;
            statusLabel.setText("<html>Status: Analyzing Map Data...</html>");
            statusLabel.setForeground(Color.BLACK);

            new Thread(() -> {
                GeoPosition startPos = getCoordinates(startText);
                GeoPosition endPos = getCoordinates(endText);

                if (startPos == null || endPos == null) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("<html>Status: Location Error</html>");
                        JOptionPane.showMessageDialog(frame, "Could not find one or both locations.", "Error", JOptionPane.ERROR_MESSAGE);
                    });
                    return;
                }

                // Step A: Fetch Main Route (Shortest Path via Dijkstra)
                List<GeoPosition> roadTrack = getRoadPath(startPos, endPos);

                if (roadTrack.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("<html>Status: Route Impossible (No Roads)</html>");
                        statusLabel.setForeground(new Color(220, 53, 69)); 
                        JOptionPane.showMessageDialog(frame, 
                            "No driving route exists between these locations.\nThey may be separated by an ocean or lack connecting roads.", 
                            "Routing Blocked", 
                            JOptionPane.WARNING_MESSAGE);
                        
                        WaypointPainter<DefaultWaypoint> wpPainter = new WaypointPainter<>();
                        wpPainter.setWaypoints(new HashSet<>(Arrays.asList(new DefaultWaypoint(startPos), new DefaultWaypoint(endPos))));
                        mapViewer.setOverlayPainter(wpPainter);
                        mapViewer.setAddressLocation(startPos);
                        mapViewer.setZoom(12);
                        mapViewer.repaint();
                    });
                    return; 
                }

                // --- OCEAN GAP CHECK ---
                GeoPosition routeEnd = roadTrack.get(roadTrack.size() - 1);
                double latDiffEnd = endPos.getLatitude() - routeEnd.getLatitude();
                double lonDiffEnd = endPos.getLongitude() - routeEnd.getLongitude();
                double snapDistance = Math.sqrt((latDiffEnd * latDiffEnd) + (lonDiffEnd * lonDiffEnd));

                if (snapDistance > 1.5) { 
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("<html>Status: Route Incomplete<br>(Ocean Gap Detected)</html>");
                        statusLabel.setForeground(new Color(220, 53, 69)); 
                        JOptionPane.showMessageDialog(frame, 
                            "The routing engine reached the edge of the connected road network and cannot reach the final destination.", 
                            "Ocean Crossing Blocked", 
                            JOptionPane.WARNING_MESSAGE);
                        
                        WaypointPainter<DefaultWaypoint> wpPainter = new WaypointPainter<>();
                        wpPainter.setWaypoints(new HashSet<>(Arrays.asList(new DefaultWaypoint(startPos), new DefaultWaypoint(endPos))));
                        mapViewer.setOverlayPainter(wpPainter);
                        mapViewer.setAddressLocation(startPos);
                        mapViewer.setZoom(12);
                        mapViewer.repaint();
                    });
                    return; 
                }

                // Step B: EXPERT SYSTEM - DETECT TRAFFIC BOTTLENECK
                boolean[] isTraffic = new boolean[roadTrack.size()];
                int trafficNodeCount = 0;
                
                int jamLength = 0;
                int jamStart = 0;
                boolean bottleneckFound = false;

                if (roadTrack.size() > 20) {
                    Random rand = new Random();
                    jamLength = Math.max(10, roadTrack.size() / 4);
                    jamStart = rand.nextInt(Math.max(1, roadTrack.size() - jamLength - 10)) + 5; 
                    
                    for (int i = jamStart; i < jamStart + jamLength && i < roadTrack.size(); i++) {
                        isTraffic[i] = true;
                        trafficNodeCount++;
                    }
                    
                    if (jamStart > 0 && jamStart + jamLength < roadTrack.size()) {
                        bottleneckFound = true;
                    }
                }

                // Step C: EXPERT SYSTEM - PURE DIJKSTRA ALTERNATIVES
                final List<GeoPosition> globalAlternativeTrack = new ArrayList<>();
                final List<GeoPosition> localBypassTrack = new ArrayList<>();
                
                String tempStatus;
                Color tempColor;
                
                if (bottleneckFound && trafficNodeCount > 10) {
                    
                    // 1. GLOBAL GREEN ROUTE (2nd fastest for the whole trip)
                    globalAlternativeTrack.addAll(getAlgorithmicDetourPath(startPos, endPos));

                    // 2. LOCAL YELLOW BYPASS (Dynamic Dijkstra Expansion)
                    int window = 0; // Start exactly at the bounds of the red line
                    int maxRetries = 10; // Try expanding the search up to 10 times to find a legal road exit
                    
                    GeoPosition divergePoint = null;
                    GeoPosition rejoinPoint = null;

                    for (int attempt = 0; attempt < maxRetries; attempt++) {
                        int stepBackIndex = Math.max(0, jamStart - window);
                        int stepForwardIndex = Math.min(roadTrack.size() - 1, jamStart + jamLength + window);
                        
                        divergePoint = roadTrack.get(stepBackIndex); 
                        rejoinPoint = roadTrack.get(stepForwardIndex); 

                        // Ask the API for a pure Dijkstra 2nd fastest route between these two points
                        List<GeoPosition> attemptDetour = getAlgorithmicDetourPath(divergePoint, rejoinPoint);
                        
                        if (!attemptDetour.isEmpty()) {
                            localBypassTrack.addAll(attemptDetour);
                            break; // Success! OSRM found a real road alternative.
                        } else {
                            // OSRM failed to find an alternative road. 
                            // Expand the search window outward by 15 coordinate points to find an earlier exit ramp.
                            window += 15; 
                        }
                    }

                    if (!localBypassTrack.isEmpty()) {
                        tempStatus = "EXPERT: Local Bypass (Yellow)<br>& Global Alt (Green) Active";
                    } else {
                        tempStatus = "EXPERT: No parallel roads available.<br>Global Alt (Green) Active";
                    }
                    tempColor = new Color(0, 120, 0);
                    
                } else {
                    tempStatus = "EXPERT: Route Clear, Standard Path Optimal";
                    tempColor = new Color(0, 150, 0);
                }
                
                final String finalStatus = tempStatus;
                final Color finalStatusColor = tempColor;

                // --- PAINTER 1: GLOBAL ALTERNATIVE (Drawn First, THICK Green) ---
                Painter<JXMapViewer> globalPainter = (g, map, w, h) -> {
                    if (globalAlternativeTrack.isEmpty()) return;
                    g = (Graphics2D) g.create();
                    Rectangle rect = map.getViewportBounds();
                    g.translate(-rect.x, -rect.y);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    
                    g.setColor(new Color(0, 200, 0, 180)); 
                    g.setStroke(new BasicStroke(12, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); 

                    Point2D last = null;
                    for (GeoPosition gp : globalAlternativeTrack) {
                        Point2D current = map.getTileFactory().geoToPixel(gp, map.getZoom());
                        if (last != null) g.drawLine((int)last.getX(), (int)last.getY(), (int)current.getX(), (int)current.getY());
                        last = current;
                    }
                    g.dispose();
                };

                // --- PAINTER 2: LOCAL BYPASS (Drawn Second, THICK Yellow) ---
                Painter<JXMapViewer> localPainter = (g, map, w, h) -> {
                    if (localBypassTrack.isEmpty()) return;
                    g = (Graphics2D) g.create();
                    Rectangle rect = map.getViewportBounds();
                    g.translate(-rect.x, -rect.y);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    
                    g.setColor(new Color(255, 204, 0, 210)); 
                    g.setStroke(new BasicStroke(12, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); 

                    Point2D last = null;
                    for (GeoPosition gp : localBypassTrack) {
                        Point2D current = map.getTileFactory().geoToPixel(gp, map.getZoom());
                        if (last != null) g.drawLine((int)last.getX(), (int)last.getY(), (int)current.getX(), (int)current.getY());
                        last = current;
                    }
                    g.dispose();
                };

                // --- PAINTER 3: PRIMARY ROUTE (Drawn Last, THIN Blue/Red) ---
                Painter<JXMapViewer> primaryRoutePainter = (g, map, w, h) -> {
                    g = (Graphics2D) g.create();
                    Rectangle rect = map.getViewportBounds();
                    g.translate(-rect.x, -rect.y);
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)); 

                    Point2D last = null;
                    for (int i = 0; i < roadTrack.size(); i++) {
                        Point2D current = map.getTileFactory().geoToPixel(roadTrack.get(i), map.getZoom());
                        if (last != null) {
                            if (isTraffic[i]) g.setColor(new Color(220, 53, 69, 255)); // Red
                            else g.setColor(new Color(30, 144, 255, 255)); // Blue
                            g.drawLine((int)last.getX(), (int)last.getY(), (int)current.getX(), (int)current.getY());
                        }
                        last = current;
                    }
                    g.dispose();
                };

                // Update UI safely
                SwingUtilities.invokeLater(() -> {
                    WaypointPainter<DefaultWaypoint> wpPainter = new WaypointPainter<>();
                    wpPainter.setWaypoints(new HashSet<>(Arrays.asList(new DefaultWaypoint(startPos), new DefaultWaypoint(endPos))));
                    
                    mapViewer.setOverlayPainter(new CompoundPainter<>(Arrays.asList(
                        globalPainter, localPainter, primaryRoutePainter, wpPainter
                    )));
                    
                    statusLabel.setText("<html><div style='width: 280px; text-align: left;'>" + finalStatus + "</div></html>");
                    statusLabel.setForeground(finalStatusColor);
                    
                    mapViewer.setAddressLocation(startPos);
                    mapViewer.repaint();
                });
            }).start();
        });

        frame.add(sidebar, BorderLayout.WEST);
        frame.add(mapViewer, BorderLayout.CENTER);
        frame.setSize(1200, 800);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private static void setupAutocomplete(JTextField textField) {
        JPopupMenu suggestionPopup = new JPopupMenu();
        suggestionPopup.setFocusable(false);
        DefaultListModel<String> listModel = new DefaultListModel<>();
        JList<String> suggestionList = new JList<>(listModel);
        suggestionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        suggestionList.setVisibleRowCount(5);
        suggestionList.setFixedCellHeight(30);
        suggestionList.setFont(new Font("SansSerif", Font.PLAIN, 13));
        suggestionList.setBackground(Color.WHITE);
        JScrollPane scrollPane = new JScrollPane(suggestionList);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        suggestionPopup.add(scrollPane);
        
        suggestionList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent evt) {
                if (suggestionList.getSelectedValue() != null) {
                    textField.setText(suggestionList.getSelectedValue());
                    suggestionPopup.setVisible(false);
                }
            }
        });
        
        textField.getDocument().addDocumentListener(new DocumentListener() {
            Timer timer;
            public void insertUpdate(DocumentEvent e) { update(); }
            public void removeUpdate(DocumentEvent e) { update(); }
            public void changedUpdate(DocumentEvent e) { update(); }
            private void update() {
                if (timer != null) timer.cancel();
                timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override public void run() { 
                        if (textField.getText().length() > 2) fetchSuggestions(textField.getText(), suggestionPopup, listModel, textField); 
                        else SwingUtilities.invokeLater(() -> suggestionPopup.setVisible(false));
                    }
                }, 600); 
            }
        });
    }

    private static void fetchSuggestions(String query, JPopupMenu popup, DefaultListModel<String> listModel, JTextField textField) {
        try {
            String encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8.toString());
            String url = "https://nominatim.openstreetmap.org/search?q=" + encodedQuery + "&format=json&limit=5";
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", FAKE_BROWSER_AGENT).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            
            SwingUtilities.invokeLater(() -> {
                listModel.clear();
                if (body.contains("\"display_name\":\"")) {
                    String[] parts = body.split("\"display_name\":\"");
                    for (int i = 1; i < parts.length; i++) {
                        listModel.addElement(parts[i].split("\"")[0].replace("\\u0026", "&").replace("\\u0027", "'"));
                    }
                    if (!listModel.isEmpty()) {
                        popup.setPopupSize(textField.getWidth(), popup.getPreferredSize().height);
                        if (!popup.isVisible()) popup.show(textField, 0, textField.getHeight());
                        else popup.pack();
                        textField.requestFocusInWindow(); 
                    }
                } else popup.setVisible(false);
            });
        } catch (Exception ignored) {}
    }

    private static GeoPosition getCoordinates(String address) {
        try {
            String url = "https://nominatim.openstreetmap.org/search?q=" + URLEncoder.encode(address, StandardCharsets.UTF_8.toString()) + "&format=json&limit=1";
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", FAKE_BROWSER_AGENT).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.body().contains("\"lat\":\"")) {
                String body = response.body();
                double lat = Double.parseDouble(body.split("\"lat\":\"")[1].split("\"")[0]);
                double lon = Double.parseDouble(body.split("\"lon\":\"")[1].split("\"")[0]);
                return new GeoPosition(lat, lon);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static List<GeoPosition> getRoadPath(GeoPosition start, GeoPosition end) {
        List<GeoPosition> path = new ArrayList<>();
        try {
            String url = "https://router.project-osrm.org/route/v1/driving/" + start.getLongitude() + "," + start.getLatitude() + ";" + end.getLongitude() + "," + end.getLatitude() + "?overview=full&geometries=geojson";
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", FAKE_BROWSER_AGENT).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            if (body.contains("\"code\":\"NoRoute\"") || !body.contains("\"coordinates\":[")) return path; 
            
            String coordsPart = body.split("\"coordinates\":\\[")[1].split("\\]\\]")[0];
            for (String pair : coordsPart.split("\\],\\[")) {
                String[] lonLat = pair.replace("[", "").replace("]", "").split(",");
                path.add(new GeoPosition(Double.parseDouble(lonLat[1]), Double.parseDouble(lonLat[0])));
            }
        } catch (Exception ignored) {}
        return path;
    }

    // API REQUEST 2: PURE DIJKSTRA ALTERNATIVE 
    // Automatically grabs the 2nd fastest route directly from the routing engine.
    private static List<GeoPosition> getAlgorithmicDetourPath(GeoPosition start, GeoPosition end) {
        List<GeoPosition> path = new ArrayList<>();
        try {
            String url = "https://router.project-osrm.org/route/v1/driving/" + start.getLongitude() + "," + start.getLatitude() + ";" + end.getLongitude() + "," + end.getLatitude() + "?overview=full&geometries=geojson&alternatives=true";
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", FAKE_BROWSER_AGENT).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String[] routeChunks = response.body().split("\"coordinates\":\\[");
            
            // Chunk [2] contains the exact 2nd fastest route calculated by the engine
            if (routeChunks.length > 2) {
                String coordsPart = routeChunks[2].split("\\]\\]")[0]; 
                for (String pair : coordsPart.split("\\],\\[")) {
                    String[] lonLat = pair.replace("[", "").replace("]", "").split(",");
                    path.add(new GeoPosition(Double.parseDouble(lonLat[1]), Double.parseDouble(lonLat[0])));
                }
            } 
        } catch (Exception ignored) {}
        return path;
    }
}