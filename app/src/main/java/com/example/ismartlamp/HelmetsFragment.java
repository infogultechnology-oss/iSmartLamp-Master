package com.example.ismartlamp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HelmetsFragment extends Fragment {

    private View homeBgCircle, listBgCircle, infoBgCircle;
    private LinearLayout lampListContainer;
    private DatabaseHelper dbHelper;

    // FIXED: these values now match DateListFragment / GraphActivity / DatabaseHelper.
    // Previously this map used different numbers (O2<19.5, CO>50, Temp>50 in
    // hasDangerousData below), so the "danger dot" here could disagree with
    // the per-date and graph danger indicators for the exact same data.
    private static final Map<String, Float> GAS_THRESHOLDS = new HashMap<String, Float>() {{
        put("O2", 29.5f);
        put("CO", 35.0f);
        put("H2S", 10.0f);
        put("CH4", 1000f);
        put("Temp", 17f);
        put("Hum", 80f);
    }};

    public HelmetsFragment() {
        // Required empty public constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_helmets, container, false);

        lampListContainer = view.findViewById(R.id.lampListContainer);
        dbHelper = new DatabaseHelper(getContext());

        loadLampsFromDatabase();

        return view;
    }

    private void loadLampsFromDatabase() {
        List<String> allLampNames = dbHelper.getAllLampNames();
        Set<String> uniqueNames = new HashSet<>(allLampNames);
        SharedPreferences prefs = requireContext().getSharedPreferences("HelmetPrefs", getContext().MODE_PRIVATE);

        // FIXED: getStringSet() returns an instance that must not be mutated
        // directly (Android's docs warn this can throw UnsupportedOperationException,
        // and writing the same mutated instance back is unreliable). Copy it
        // into a new HashSet first.
        Set<String> seenHelmets = new HashSet<>(prefs.getStringSet("seen_helmets", new HashSet<>()));

        LayoutInflater inflater = LayoutInflater.from(getContext());
        lampListContainer.removeAllViews();

        for (String lampName : uniqueNames) {
            View itemView = inflater.inflate(R.layout.item_layout, lampListContainer, false);
            TextView nameView = itemView.findViewById(R.id.helmetName);
            nameView.setText(lampName);
            TextView subInfo = itemView.findViewById(R.id.helmetSubInfo);
            String lastSynced = dbHelper.getLastSyncedDate(lampName);
            if (lastSynced != null) {
                subInfo.setText("Last synced: " + lastSynced.split(" ")[0]);
            } else {
                subInfo.setText("No sync yet");
            }

            ImageView dangerDot = itemView.findViewById(R.id.dangerDot);
            boolean isDangerous = hasDangerousData(lampName);

            if (isDangerous && !seenHelmets.contains(lampName)) {
                dangerDot.setVisibility(View.VISIBLE);
            } else {
                dangerDot.setVisibility(View.GONE);
            }

            itemView.setOnClickListener(v -> {
                seenHelmets.add(lampName);
                prefs.edit().putStringSet("seen_helmets", seenHelmets).apply();

                DateListFragment dateListFragment = new DateListFragment();
                Bundle args = new Bundle();
                args.putString("lamp_name", lampName);
                dateListFragment.setArguments(args);

                requireActivity()
                        .getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, dateListFragment)
                        .addToBackStack(null)
                        .commit();



            });

            lampListContainer.addView(itemView);
        }
    }

    private boolean hasDangerousData(String lampName) {
        List<SensorRecord> records = dbHelper.getSensorData(lampName);
        for (SensorRecord record : records) {
            // FIXED: was hardcoded (O2<19.5, CO>50, Temp>50) — now matches
            // the GAS_THRESHOLDS map above and the values used elsewhere.
            if (record.getO2() < GAS_THRESHOLDS.get("O2") || record.getCo() > GAS_THRESHOLDS.get("CO") ||
                    record.getH2s() > GAS_THRESHOLDS.get("H2S") || record.getCh4() > GAS_THRESHOLDS.get("CH4") ||
                    record.getTemp() > GAS_THRESHOLDS.get("Temp") || record.getHum() > GAS_THRESHOLDS.get("Hum")) {
                return true;
            }
        }
        return false;
    }
}
