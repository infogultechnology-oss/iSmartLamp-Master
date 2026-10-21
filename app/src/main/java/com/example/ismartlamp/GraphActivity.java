package com.example.ismartlamp;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import android.os.Environment;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Map;


public class GraphActivity extends AppCompatActivity {

    private LineChart lineChart;
    private DatabaseHelper dbHelper;
    private List<String> timestamps = new ArrayList<>();
    private List<Float> gasValues = new ArrayList<>();
    private String selectedLampName;
    private String selectedGasLabel  = "o2";
    private boolean isFilterActive = false;
    private String fromTime = null;
    private String toTime = null;
    private String selectedDate;


    private static final Map<String, Float> GAS_THRESHOLDS = new HashMap<String, Float>() {{
        put("O2", 29.5f);    // below this is dangerous
        put("CO", 35.0f);    // above this is dangerous
        put("H2S", 10.0f);   // above this is dangerous
        put("CH4", 1000f);   // above this is dangerous (ppm)
        put("Temp", 17f);    // example
        put("Hum", 80f);     // example
    }};

    // Units shown on the chart and in the tap-marker popup, so a "20" on
    // screen actually means something (20 %Vol vs 20 ppm are very different).
    private static final Map<String, String> GAS_UNITS = new HashMap<String, String>() {{
        put("O2", "%Vol");
        put("CO", "ppm");
        put("H2S", "ppm");
        put("CH4", "%LEL");
        put("Temp", "°C");
        put("Hum", "%RH");
    }};

    private View homeBgCircle, listBgCircle, infoBgCircle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_graph);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);

            final WindowInsetsController insetsController = getWindow().getInsetsController();
            if (insetsController != null) {
                insetsController.hide(WindowInsets.Type.statusBars());
                insetsController.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }

        selectedDate = getIntent().getStringExtra("selectedDate");
        lineChart = findViewById(R.id.linechart);
        dbHelper = new DatabaseHelper(this);
        selectedLampName = getIntent().getStringExtra("lamp_name");

        android.util.Log.d("GraphActivity", "✅ Received -> Lamp: " + selectedLampName + ", Date: " + selectedDate);
        if (selectedLampName == null || selectedDate == null) {
            Toast.makeText(this, "Missing data", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        ImageButton filterButton = findViewById(R.id.btnFilter);
        filterButton.setOnClickListener(v -> showFilterDialog());

        findViewById(R.id.btnExport).setOnClickListener(v -> exportAllGasDataToCSV());



        setupTabs();
        updateGraph("O2");
    }


    private void showFilterDialog() {
        if (timestamps == null || timestamps.isEmpty()) {
            Toast.makeText(this, "No data available for filtering", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.filter_dailogue, null);
        Spinner spinnerFrom = dialogView.findViewById(R.id.spinnerFromTime);
        Spinner spinnerTo = dialogView.findViewById(R.id.spinnerToTime);

        Pair<String, String> range = getMinAndMaxTimeRange();
        List<String> availableTimes = getAvailableTimes(range.first, range.second);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, availableTimes);
        spinnerFrom.setAdapter(adapter);
        spinnerTo.setAdapter(adapter);

        new AlertDialog.Builder(this)
                .setTitle("Filter Data")
                .setView(dialogView)
                .setPositiveButton("Apply", (dialog, which) -> {
                    String from = spinnerFrom.getSelectedItem().toString();
                    String to = spinnerTo.getSelectedItem().toString();

                    if (from.compareTo(to) <= 0) {
                        fromTime = from;
                        toTime = to;
                        isFilterActive = true;
                        filterGraphByTimeRange(fromTime, toTime);
                    } else {
                        Toast.makeText(this, "Invalid range: 'From' time must be earlier", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Reset", (dialog, which) -> {
                    fromTime = null;
                    toTime = null;
                    isFilterActive = false;
                    updateGraph(selectedGasLabel);
                    Toast.makeText(this, "Filter cleared", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void exportAllGasDataToCSV() {
        List<SensorRecord> records = dbHelper.getSensorData(selectedLampName);

        if (records == null || records.isEmpty()) {
            Toast.makeText(this, "No data available to export", Toast.LENGTH_SHORT).show();
            return;
        }

        // FIXED: was " ".split(datetime)[0], which is backwards — it split the
        // literal string " " using the datetime as a regex, instead of
        // splitting the datetime on a space. Now correctly extracts the date
        // portion of the timestamp.
        String firstDate = records.get(0).getDatetime().split(" ")[0];
        String filename = selectedLampName + "_" + firstDate + ".csv";

        File exportDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!exportDir.exists()) exportDir.mkdirs();

        File file = new File(exportDir, filename);

        try (FileWriter writer = new FileWriter(file)) {
            // Header
            writer.append("Timestamp,O2,CO,H2S,CH4,Temp,Hum\n");

            for (SensorRecord record : records) {
                writer.append(record.getDatetime()).append(",")
                        .append(String.valueOf(record.getO2())).append(",")
                        .append(String.valueOf(record.getCo())).append(",")
                        .append(String.valueOf(record.getH2s())).append(",")
                        .append(String.valueOf(record.getCh4())).append(",")
                        .append(String.valueOf(record.getTemp())).append(",")
                        .append(String.valueOf(record.getHum())).append("\n");
            }

            writer.flush();
            Toast.makeText(this, "Exported to: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();

        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }







    private Pair<String, String> getMinAndMaxTimeRange() {
        DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("HH:mm");

        LocalTime min = null;
        LocalTime max = null;

        for (String ts : timestamps) {
            try {
                LocalDateTime dt = LocalDateTime.parse(ts, inputFormatter);
                LocalTime time = dt.toLocalTime();

                if (min == null || time.isBefore(min)) min = time;
                if (max == null || time.isAfter(max)) max = time;

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (min != null && max != null) {
            return new Pair<>(min.format(outputFormatter), max.format(outputFormatter));
        } else {
            return new Pair<>("00:00", "23:59");
        }
    }


    private List<String> getAvailableTimes(String start, String end) {
        List<String> times = new ArrayList<>();
        int startHour = Integer.parseInt(start.split(":")[0]);
        int endHour = Integer.parseInt(end.split(":")[0]);

        for (int hour = startHour; hour <= endHour; hour++) {
            times.add(String.format("%02d:00", hour));
            times.add(String.format("%02d:30", hour));
        }

        return times;
    }

    class SensorEntry {
        float value;
        String datetime; // Example: "2025-05-11 23:19:25"
    }


    private void setupTabs() {
        findViewById(R.id.tabGas1).setOnClickListener(v -> updateGraph("O2"));
        findViewById(R.id.tabGas2).setOnClickListener(v -> updateGraph("CO"));
        findViewById(R.id.tabGas3).setOnClickListener(v -> updateGraph("H2S"));
        findViewById(R.id.tabGas4).setOnClickListener(v -> updateGraph("CH4"));
        findViewById(R.id.tabGas5).setOnClickListener(v -> updateGraph("Temp"));
        findViewById(R.id.tabGas6).setOnClickListener(v -> updateGraph("Hum"));
    }
    private void updateGraph(String gasType) {
        selectedGasLabel = gasType;
        List<SensorRecord> records = dbHelper.getSensorDataByDate(selectedLampName, selectedDate);
        timestamps.clear();
        gasValues.clear();

        for (SensorRecord record : records) {
            timestamps.add(record.getDatetime());
            switch (gasType) {
                case "O2": gasValues.add(record.getO2()); break;
                case "CO": gasValues.add(record.getCo()); break;
                case "H2S": gasValues.add(record.getH2s()); break;
                case "CH4": gasValues.add(record.getCh4()); break;
                case "Temp": gasValues.add(record.getTemp()); break;
                case "Hum": gasValues.add(record.getHum()); break;
            }
        }

        if (isFilterActive && fromTime != null && toTime != null) {
            filterGraphByTimeRange(fromTime, toTime);
            highlightTab(gasType);
            return;
        }

        List<Entry> entries = new ArrayList<>();
        List<Integer> circleColors = new ArrayList<>();

        float threshold = GAS_THRESHOLDS.getOrDefault(gasType, Float.MAX_VALUE);

        for (int i = 0; i < gasValues.size(); i++) {
            float val = gasValues.get(i);
            entries.add(new Entry(i, val));

            boolean isDangerous;
            if (gasType.equals("O2")) {
                isDangerous = val < threshold; // Low O2 is dangerous
            } else {
                isDangerous = val > threshold; // High is dangerous for others
            }

            circleColors.add(isDangerous ? Color.RED : Color.GREEN);
        }

        LineDataSet dataSet = new LineDataSet(entries, gasType + " Levels (" + GAS_UNITS.getOrDefault(gasType, "") + ")");
        dataSet.setColor(Color.BLUE);
        dataSet.setLineWidth(2f);
        dataSet.setCircleRadius(5f);
        dataSet.setCircleColors(circleColors); // Red or Green based on threshold
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setDrawCircles(true); // Show only red/green circles
        dataSet.setDrawFilled(true);
        dataSet.setFillAlpha(100);
        dataSet.setFillColor(Color.BLUE);

        LineData lineData = new LineData(dataSet);
        lineChart.setData(lineData);

        lineChart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        lineChart.getXAxis().setGranularity(1f);
        lineChart.getXAxis().setDrawGridLines(false);
        lineChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int index = (int) value;
                return index >= 0 && index < timestamps.size() ? timestamps.get(index).split(" ")[1] : "";
            }
        });

        YAxis leftAxis = lineChart.getAxisLeft();
        leftAxis.setDrawGridLines(true);

        XAxis xAxis = lineChart.getXAxis();
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(Color.DKGRAY);
        xAxis.setTextSize(12f);
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);

        leftAxis.setGridColor(Color.LTGRAY);
        leftAxis.setTextColor(Color.DKGRAY);
        leftAxis.setTextSize(12f);

        lineChart.getAxisRight().setEnabled(false);
        lineChart.animateX(1000);

        Description desc = new Description();
        desc.setText("Timestamp vs " + gasType + " (" + GAS_UNITS.getOrDefault(gasType, "") + ")");
        desc.setTextColor(Color.WHITE);
        desc.setTextSize(10f);
        lineChart.setDescription(desc);

        CustomMarkerView marker = new CustomMarkerView(this, R.layout.marker_view_layout, timestamps, selectedGasLabel, lineChart);
        lineChart.setMarker(marker);

        lineChart.invalidate();

        highlightTab(gasType);
        updateTabAlerts();

        lineChart.fitScreen();
        lineChart.zoom(6f, 0f, 0f, 0f); // zoomX, zoomY, x-value, y-value


    }


    private void updateTabAlerts() {
        List<SensorRecord> records = dbHelper.getSensorData(selectedLampName);

        boolean dangerO2 = false, dangerCO = false, dangerH2S = false, dangerCH4 = false, dangerTemp = false, dangerHum = false;

        for (SensorRecord record : records) {
            if (record.getO2() < GAS_THRESHOLDS.get("O2")) dangerO2 = true;
            if (record.getCo() > GAS_THRESHOLDS.get("CO")) dangerCO = true;
            if (record.getH2s() > GAS_THRESHOLDS.get("H2S")) dangerH2S = true;
            if (record.getCh4() > GAS_THRESHOLDS.get("CH4")) dangerCH4 = true;
            if (record.getTemp() > GAS_THRESHOLDS.get("Temp")) dangerTemp = true;
            if (record.getHum() > GAS_THRESHOLDS.get("Hum")) dangerHum = true;
        }

        findViewById(R.id.redDotO2).setVisibility(dangerO2 ? View.VISIBLE : View.GONE);
        findViewById(R.id.redDotCO).setVisibility(dangerCO ? View.VISIBLE : View.GONE);
        findViewById(R.id.redDotH2S).setVisibility(dangerH2S ? View.VISIBLE : View.GONE);
        findViewById(R.id.redDotCH4).setVisibility(dangerCH4 ? View.VISIBLE : View.GONE);
        findViewById(R.id.redDotTemp).setVisibility(dangerTemp ? View.VISIBLE : View.GONE);
        findViewById(R.id.redDotHum).setVisibility(dangerHum ? View.VISIBLE : View.GONE);
    }




    private void filterGraphByTimeRange(String fromTime, String toTime) {
        DateTimeFormatter fullFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        DateTimeFormatter timeOnlyFormat = DateTimeFormatter.ofPattern("HH:mm");

        LocalTime from = LocalTime.parse(fromTime, timeOnlyFormat);
        LocalTime to = LocalTime.parse(toTime, timeOnlyFormat);

        List<Entry> filteredEntries = new ArrayList<>();
        List<Integer> circleColors = new ArrayList<>();
        List<String> filteredTimestamps = new ArrayList<>();

        float threshold = GAS_THRESHOLDS.getOrDefault(selectedGasLabel, Float.MAX_VALUE);
        int index = 0;

        for (int i = 0; i < timestamps.size(); i++) {
            String fullTimestamp = timestamps.get(i);
            float value = gasValues.get(i);

            try {
                LocalDateTime dateTime = LocalDateTime.parse(fullTimestamp, fullFormat);
                LocalTime currentTime = dateTime.toLocalTime();

                if (!currentTime.isBefore(from) && !currentTime.isAfter(to)) {
                    filteredEntries.add(new Entry(index, value));

                    boolean isDangerous = selectedGasLabel.equals("O2") ? value < threshold : value > threshold;
                    circleColors.add(isDangerous ? Color.RED : Color.GREEN);

                    filteredTimestamps.add(fullTimestamp);
                    index++;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (filteredEntries.isEmpty()) {
            Toast.makeText(this, "No data in selected range", Toast.LENGTH_SHORT).show();
            return;
        }

        LineDataSet dataSet = new LineDataSet(filteredEntries, selectedGasLabel + " Levels (Filtered) (" + GAS_UNITS.getOrDefault(selectedGasLabel, "") + ")");
        dataSet.setColor(Color.BLUE);
        dataSet.setCircleRadius(5f);
        dataSet.setDrawValues(false);
        dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
        dataSet.setDrawFilled(true);
        dataSet.setDrawCircles(true);
        dataSet.setFillAlpha(100);
        dataSet.setFillColor(Color.BLUE);
        dataSet.setCircleColors(circleColors);

        LineData lineData = new LineData(dataSet);
        lineChart.setData(lineData);

        lineChart.getXAxis().setValueFormatter(new ValueFormatter() {
            @Override
            public String getFormattedValue(float value) {
                int i = (int) value;
                return i >= 0 && i < filteredTimestamps.size() ? filteredTimestamps.get(i).split(" ")[1] : "";
            }
        });

        Description desc = new Description();
        desc.setText("Filtered: " + fromTime + " - " + toTime);
        lineChart.setDescription(desc);

        lineChart.fitScreen(); // reset zoom before applying new one
        lineChart.zoom(1.5f, 1.2f, 0f, 0f);

        CustomMarkerView marker = new CustomMarkerView(this, R.layout.marker_view_layout, filteredTimestamps, selectedGasLabel, lineChart);
        lineChart.setMarker(marker);

        lineChart.invalidate();
    }



    private void highlightTab(String selectedGas) {
        int selectedBg = R.drawable.tab_background_selected;
        int unselectedBg = R.drawable.tab_background_unselected;

        TextView[] tabs = {
                findViewById(R.id.tabGas1),
                findViewById(R.id.tabGas2),
                findViewById(R.id.tabGas3),
                findViewById(R.id.tabGas4),
                findViewById(R.id.tabGas5),
                findViewById(R.id.tabGas6)
        };

        for (TextView tab : tabs) {
            tab.setBackgroundResource(unselectedBg);
            tab.setTextColor(Color.GRAY);
        }

        int selectedId = R.id.tabGas1;
        switch (selectedGas) {
            case "O2": selectedId = R.id.tabGas1; break;
            case "CO": selectedId = R.id.tabGas2; break;
            case "H2S": selectedId = R.id.tabGas3; break;
            case "CH4": selectedId = R.id.tabGas4; break;
            case "Temp": selectedId = R.id.tabGas5; break;
            case "Hum": selectedId = R.id.tabGas6; break;
        }

        TextView selectedTab = findViewById(selectedId);
        selectedTab.setBackgroundResource(selectedBg);
        selectedTab.setTextColor(Color.WHITE);
    }
    public class CustomMarkerView extends MarkerView {
        private final TextView text;
        private final List<String> timeLabels;
        private final String gasLabel;
        private final LineChart chart; // <-- Add this line

        public CustomMarkerView(Context context, int layoutResource, List<String> timeLabels, String gasLabel, LineChart chart) {
            super(chart.getContext(), layoutResource); // If this fails, change to -> super(chart.getContext(), layoutResource);
            this.timeLabels = timeLabels;
            this.gasLabel = gasLabel;
            this.chart = chart;
            text = findViewById(R.id.markerText);
        }

        @Override
        public void refreshContent(Entry e, Highlight highlight) {
            int index = (int) e.getX();
            String time = index < timeLabels.size() ? timeLabels.get(index) : "";
            String unit = GAS_UNITS.getOrDefault(gasLabel, "");
            text.setText(String.format("%s: %.2f %s\nTime: %s", gasLabel, e.getY(), unit, time));
            super.refreshContent(e, highlight);
        }

        @Override
        public MPPointF getOffsetForDrawingAtPoint(float posX, float posY) {
            if (getWidth() == 0 || getHeight() == 0) {
                measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            }

            float width = getWidth();
            float height = getHeight();

            MPPointF offset = new MPPointF();

            // Use local chart reference instead of getChartView()
            if (chart != null && (posX + width) > chart.getWidth()) {
                offset.x = -width;
            } else {
                offset.x = 0;
            }

            offset.y = -height;
            return offset;
        }

        @Override
        public MPPointF getOffset() {
            if (getWidth() == 0 || getHeight() == 0) {
                measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            }
            return new MPPointF(-(getWidth() / 2f), -getHeight());
        }
    }
}