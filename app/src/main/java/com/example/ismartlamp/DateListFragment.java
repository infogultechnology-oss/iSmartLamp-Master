// DateListFragment.java
package com.example.ismartlamp;

import static androidx.constraintlayout.motion.utils.Oscillator.TAG;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

public class DateListFragment extends Fragment {

    private LinearLayout dateListContainer;
    private DatabaseHelper dbHelper;
    private String lampName;
    private static final Map<String, Float> GAS_THRESHOLDS = new HashMap<String, Float>() {{
        put("O2", 29.5f);
        put("CO", 35.0f);
        put("H2S", 10.0f);
        put("CH4", 1000f);
        put("Temp", 17f);
        put("Hum", 80f);
    }};

    public static DateListFragment newInstance(String lampName) {
        DateListFragment fragment = new DateListFragment();
        Bundle args = new Bundle();
        args.putString("lamp_name", lampName);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_date_list, container, false);
        dateListContainer = view.findViewById(R.id.dateListContainer);
        dbHelper = new DatabaseHelper(requireContext());

        if (getArguments() != null) {
            lampName = getArguments().getString("lamp_name");
            Log.d(TAG, "Received lamp name: " + lampName);
        }

        if (lampName != null) {
            loadDates(inflater);
        } else {
            Toast.makeText(getContext(), "No lamp name provided", Toast.LENGTH_SHORT).show();
        }

        return view;
    }

    private void loadDates(LayoutInflater inflater) {
        List<String> dates = dbHelper.getDatesForLamp(lampName);
        dateListContainer.removeAllViews();

        for (String rawDate : dates) {
            View itemView = inflater.inflate(R.layout.item_date, dateListContainer, false);
            TextView dateText = itemView.findViewById(R.id.dateText);
            ImageView dangerDot = itemView.findViewById(R.id.dangerDot);
            ImageButton deleteBtn = itemView.findViewById(R.id.deleteButton);

            String displayDate = formatDate(rawDate);
            dateText.setText(displayDate);

            List<SensorRecord> records = dbHelper.getSensorDataByDate(lampName, rawDate);
            boolean isDanger = false;

            for (SensorRecord record : records) {
                if (record.getO2() < GAS_THRESHOLDS.get("O2") || record.getCo() > GAS_THRESHOLDS.get("CO") ||
                        record.getH2s() > GAS_THRESHOLDS.get("H2S") || record.getCh4() > GAS_THRESHOLDS.get("CH4") ||
                        record.getTemp() > GAS_THRESHOLDS.get("Temp") || record.getHum() > GAS_THRESHOLDS.get("Hum")) {
                    isDanger = true;
                    break;
                }
            }

            if (isDanger) dangerDot.setVisibility(View.VISIBLE);

            itemView.setOnClickListener(v -> {
                Intent intent = new Intent(getContext(), GraphActivity.class);
                intent.putExtra("lamp_name", lampName);
                intent.putExtra("selectedDate", rawDate);
                startActivity(intent);
            });






            deleteBtn.setOnClickListener(v -> confirmDelete(rawDate));
            dateListContainer.addView(itemView);
        }
    }

    private String formatDate(String inputDate) {
        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(inputDate);
            return new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(date);
        } catch (ParseException e) {
            return inputDate;
        }
    }

    private void confirmDelete(String date) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Data")
                .setMessage("Delete all data of " + formatDate(date) + "?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    dbHelper.deleteDataForLampOnDate(lampName, date);
                    loadDates(LayoutInflater.from(getContext()));
                    Toast.makeText(getContext(), "Data deleted.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
