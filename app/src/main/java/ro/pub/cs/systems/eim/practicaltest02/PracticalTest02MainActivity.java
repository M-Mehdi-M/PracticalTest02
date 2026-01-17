package ro.pub.cs.systems.eim.practicaltest02;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.HashMap;

public class PracticalTest02MainActivity extends AppCompatActivity {

    // UI Elements
    private EditText serverPortEditText, clientAddressEditText, clientPortEditText, cityEditText;
    private Button connectButton, getWeatherForecastButton;
    private Spinner informationTypeSpinner;
    private TextView weatherForecastTextView;

    // Server
    private ServerThread serverThread = null;

    // Data Cache
    private HashMap<String, WeatherForecastInformation> data = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_practical_test02_main);

        // Initialize UI Elements
        serverPortEditText = findViewById(R.id.server_port_edit_text);
        connectButton = findViewById(R.id.connect_button);
        clientAddressEditText = findViewById(R.id.client_address_edit_text);
        clientPortEditText = findViewById(R.id.client_port_edit_text);
        cityEditText = findViewById(R.id.city_edit_text);
        informationTypeSpinner = findViewById(R.id.information_type_spinner);
        getWeatherForecastButton = findViewById(R.id.get_weather_forecast_button);
        weatherForecastTextView = findViewById(R.id.weather_forecast_text_view);

        // Setup Listeners
        connectButton.setOnClickListener(v -> {
            // Prevent starting server if it is already running
            if (serverThread != null && serverThread.isAlive()) {
                Toast.makeText(getApplicationContext(), "Server is already running!", Toast.LENGTH_SHORT).show();
                return;
            }

            String serverPort = serverPortEditText.getText().toString();
            if (serverPort.isEmpty()) {
                Toast.makeText(getApplicationContext(), "Server port should be filled!", Toast.LENGTH_SHORT).show();
                return;
            }

            // Create and start the server thread
            serverThread = new ServerThread(Integer.parseInt(serverPort));
            if (serverThread.getServerSocket() == null) {
                Log.e("PracticalTest02", "Could not create server thread! The port might be busy.");
                Toast.makeText(getApplicationContext(), "Could not start server. Port might be in use.", Toast.LENGTH_LONG).show();
                return;
            }

            serverThread.start();
            Log.i("PracticalTest02", "Server started successfully on port " + serverPort);
            Toast.makeText(getApplicationContext(), "Server started on port " + serverPort, Toast.LENGTH_SHORT).show();

            // Optional: Disable the connect button after starting the server to prevent multiple starts
            // connectButton.setEnabled(false);
            // serverPortEditText.setEnabled(false);
        });

        getWeatherForecastButton.setOnClickListener(v -> {
            String clientAddress = clientAddressEditText.getText().toString();
            String clientPort = clientPortEditText.getText().toString();
            String city = cityEditText.getText().toString();
            String informationType = informationTypeSpinner.getSelectedItem().toString();

            if (clientAddress.isEmpty() || clientPort.isEmpty() || city.isEmpty()) {
                Toast.makeText(getApplicationContext(), "Client parameters (address, port, city) must be filled!", Toast.LENGTH_SHORT).show();
                return;
            }

            // Critical Check: Ensure serverThread object exists and is running
            if (serverThread == null || !serverThread.isAlive()) {
                Toast.makeText(getApplicationContext(), "Server is not running! Please press 'Connect' first.", Toast.LENGTH_LONG).show();
                return;
            }

            weatherForecastTextView.setText(""); // Clear previous result
            ClientThread clientThread = new ClientThread(clientAddress, Integer.parseInt(clientPort), city, informationType);
            clientThread.start();
        });
    }


    @Override
    protected void onDestroy() {
        Log.i("PracticalTest02", "onDestroy() called");
        if (serverThread != null) {
            serverThread.stopThread();
        }
        super.onDestroy();
    }

    // --- Server Thread ---
    private class ServerThread extends Thread {
        private ServerSocket serverSocket = null;
        private int port;

        public ServerThread(int port) {
            this.port = port;
            try {
                this.serverSocket = new ServerSocket(port);
            } catch (IOException ioException) {
                Log.e("PracticalTest02", "An exception has occurred: " + ioException.getMessage());
            }
        }

        public ServerSocket getServerSocket() {
            return serverSocket;
        }

        @Override
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Log.i("PracticalTest02", "[SERVER] Waiting for a client connection...");
                    Socket socket = serverSocket.accept();
                    Log.i("PracticalTest02", "[SERVER] A client has connected from " + socket.getInetAddress());
                    CommunicationThread communicationThread = new CommunicationThread(socket);
                    communicationThread.start();
                }
            } catch (IOException ioException) {
                Log.e("PracticalTest02", "[SERVER] An exception has occurred: " + ioException.getMessage());
            }
        }

        public void stopThread() {
            interrupt();
            if (serverSocket != null) {
                try {
                    serverSocket.close();
                } catch (IOException ioException) {
                    Log.e("PracticalTest02", "[SERVER] An exception has occurred on close: " + ioException.getMessage());
                }
            }
        }
    }

    // --- Communication Thread (handles each client) ---
    private class CommunicationThread extends Thread {
        private final Socket socket;

        public CommunicationThread(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            if (socket == null) {
                Log.e("PracticalTest02", "[COMM THREAD] Socket is null!");
                return;
            }
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);

                String city = reader.readLine();
                String informationType = reader.readLine();
                if (city == null || city.isEmpty() || informationType == null || informationType.isEmpty()) {
                    Log.e("PracticalTest02", "[COMM THREAD] Invalid city or information type received.");
                    return;
                }

                WeatherForecastInformation weatherData;
                if (data.containsKey(city)) {
                    Log.i("PracticalTest02", "[COMM THREAD] Getting weather forecast from cache for " + city);
                    weatherData = data.get(city);
                } else {
                    Log.i("PracticalTest02", "[COMM THREAD] Getting weather forecast from web service for " + city);
                    // API Call
                    String url = "https://api.openweathermap.org/data/2.5/weather?q=" + city + "&appid=" + "e03c3b32cfb5a6f7069f2ef29237d87e" + "&units=metric";
                    URL obj = new URL(url);
                    HttpURLConnection con = (HttpURLConnection) obj.openConnection();
                    con.setRequestMethod("GET");

                    StringBuilder response = new StringBuilder();
                    try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                        String inputLine;
                        while ((inputLine = in.readLine()) != null) {
                            response.append(inputLine);
                        }
                    }

                    JSONObject jsonResponse = new JSONObject(response.toString());
                    JSONObject main = jsonResponse.getJSONObject("main");
                    JSONObject wind = jsonResponse.getJSONObject("wind");

                    String temperature = main.getString("temp");
                    String windSpeed = wind.getString("speed");
                    String pressure = main.getString("pressure");
                    String humidity = main.getString("humidity");

                    weatherData = new WeatherForecastInformation(temperature, windSpeed, pressure, humidity);
                    data.put(city, weatherData);
                }

                String result;
                switch (informationType) {
                    case "temperature":
                        result = weatherData.getTemperature();
                        break;
                    case "wind_speed":
                        result = weatherData.getWindSpeed();
                        break;
                    case "pressure":
                        result = weatherData.getPressure();
                        break;
                    case "humidity":
                        result = weatherData.getHumidity();
                        break;
                    case "all":
                        result = weatherData.toString();
                        break;
                    default:
                        result = "Invalid information type";
                }

                writer.println(result);
                Log.i("PracticalTest02", "[COMM THREAD] Sent result to client: " + result);

            } catch (IOException | JSONException e) {
                Log.e("PracticalTest02", "[COMM THREAD] Error: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    Log.e("PracticalTest02", "[COMM THREAD] Could not close socket: " + e.getMessage());
                }
            }
        }
    }


    // --- Client Thread ---
    private class ClientThread extends Thread {
        private final String address;
        private final int port;
        private final String city;
        private final String informationType;

        public ClientThread(String address, int port, String city, String informationType) {
            this.address = address;
            this.port = port;
            this.city = city;
            this.informationType = informationType;
        }

        @Override
        public void run() {
            try (Socket socket = new Socket(address, port);
                 PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

                writer.println(city);
                writer.println(informationType);

                final String finalResult = reader.readLine();

                runOnUiThread(() -> weatherForecastTextView.setText(finalResult));

            } catch (IOException e) {
                Log.e("PracticalTest02", "[CLIENT THREAD] Error: " + e.getMessage());
                runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Error communicating with server.", Toast.LENGTH_SHORT).show());
            }
        }
    }

    // --- Data Model ---
    private static class WeatherForecastInformation {
        private final String temperature;
        private final String windSpeed;
        private final String pressure;
        private final String humidity;

        public WeatherForecastInformation(String temperature, String windSpeed, String pressure, String humidity) {
            this.temperature = temperature;
            this.windSpeed = windSpeed;
            this.pressure = pressure;
            this.humidity = humidity;
        }

        public String getTemperature() { return temperature; }
        public String getWindSpeed() { return windSpeed; }
        public String getPressure() { return pressure; }
        public String getHumidity() { return humidity; }

        @Override
        public String toString() {
            return "Temp: " + temperature + "°C, " +
                    "Wind: " + windSpeed + " m/s, " +
                    "Pressure: " + pressure + " hPa, " +
                    "Humidity: " + humidity + "%";
        }
    }
}

