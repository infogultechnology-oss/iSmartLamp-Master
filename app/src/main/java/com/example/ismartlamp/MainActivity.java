package com.example.ismartlamp;

import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

public class MainActivity extends AppCompatActivity {

    private View circleBg, listBgCircle, infoBgCircle;
    private ImageButton navHome, navList, navInfo, aboutinfo;
    private TextView screenTitle;

    private int currentTabIndex = 0; // 0 = Home, 1 = Helmets, 2 = Info
    private long backPressedTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Hook views
        circleBg = findViewById(R.id.circleBg);
        listBgCircle = findViewById(R.id.listBgCircle);
        infoBgCircle = findViewById(R.id.infoBgCircle);

        navHome = findViewById(R.id.navHome);
        navList = findViewById(R.id.navList);
        navInfo = findViewById(R.id.navInfo);
        aboutinfo = findViewById(R.id.infoButton);
        screenTitle = findViewById(R.id.screenTitle);

        // About button (no animation)
        aboutinfo.setOnClickListener(v -> {
            screenTitle.setText("About");
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, new AboutFragment())
                    .commit();
        });

        // Set initial tab
        selectTab("home");

        navHome.setOnClickListener(v -> selectTab("home"));
        navList.setOnClickListener(v -> selectTab("list"));
        navInfo.setOnClickListener(v -> selectTab("info"));
    }

    private void selectTab(String tab) {
        // Hide all backgrounds
        circleBg.setVisibility(View.GONE);
        listBgCircle.setVisibility(View.GONE);
        infoBgCircle.setVisibility(View.GONE);

        // Reset colors
        navHome.setColorFilter(ContextCompat.getColor(this, android.R.color.white));
        navList.setColorFilter(ContextCompat.getColor(this, android.R.color.white));
        navInfo.setColorFilter(ContextCompat.getColor(this, android.R.color.white));

        // Clear animations
        navHome.clearAnimation();
        navList.clearAnimation();
        navInfo.clearAnimation();

        Fragment fragment = null;
        int newTabIndex = currentTabIndex;

        switch (tab) {
            case "home":
                circleBg.setVisibility(View.VISIBLE);
                animateTab(navHome);
                screenTitle.setText("Home");
                fragment = new HomeFragment();
                newTabIndex = 0;
                break;
            case "list":
                listBgCircle.setVisibility(View.VISIBLE);
                animateTab(navList);
                screenTitle.setText("Helmets");
                fragment = new HelmetsFragment();
                newTabIndex = 1;
                break;
            case "info":
                infoBgCircle.setVisibility(View.VISIBLE);
                animateTab(navInfo);
                screenTitle.setText("Info");
                fragment = new InfoFragment();
                newTabIndex = 2;
                break;
        }

        if (fragment != null) {
            FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

            // Directional animations
            if (newTabIndex > currentTabIndex) {
                transaction.setCustomAnimations(
                        R.anim.slide_in_right, R.anim.slide_out_left
                );
            } else if (newTabIndex < currentTabIndex) {
                transaction.setCustomAnimations(
                        R.anim.slide_in_left, R.anim.slide_out_right
                );
            }

            transaction.replace(R.id.fragment_container, fragment);
            transaction.commit();

            currentTabIndex = newTabIndex;
        }
    }

    private void animateTab(View tab) {
        Animation anim = AnimationUtils.loadAnimation(this, R.anim.raise_up);
        tab.startAnimation(anim);
    }

    @Override
    public void onBackPressed() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            // If nested fragment is on back stack, pop it
            getSupportFragmentManager().popBackStack();
        } else if (currentTabIndex != 0) {
            // If on a top-level tab other than home, switch to home
            selectTab("home");
        } else {
            // Already on Home — handle double back to exit
            if (System.currentTimeMillis() - backPressedTime < 2000) {
                finish();
            } else {
                Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show();
                backPressedTime = System.currentTimeMillis();
            }
        }
    }


}
