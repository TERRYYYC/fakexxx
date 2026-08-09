package name.caiyao.fakegps.ui.activity;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;

import name.caiyao.fakegps.R;
import name.caiyao.fakegps.bean.Address;
import name.caiyao.fakegps.dao.TempDao;
import name.caiyao.fakegps.data.DbHelper;
import name.caiyao.fakegps.hook.ScreenListener;
import name.caiyao.fakegps.ui.fragment.CollectionFragment;
import name.caiyao.fakegps.ui.fragment.HelpFragment;
import name.caiyao.fakegps.ui.fragment.OneFragment;
import name.caiyao.fakegps.ui.fragment.ProfileEditorFragment;
import name.caiyao.fakegps.ui.fragment.SettingFragment;
import name.caiyao.fakegps.util.MyToast;

public class MainActivity extends AppCompatActivity implements CollectionFragment.CalbackValue {

    private TextView tv_count;
    private MapView mapView;
    private IMapController mapController;
    static GeoPoint tempGeoPoint;
    private int lac = 0, cid = 0;
    private SQLiteDatabase mSQLiteDatabase;
    static public List<GeoPoint> onMatchIntList = new ArrayList<>();

    NavigationView mNavView;
    DrawerLayout mDrawerLayout;
    Toolbar toolbar;
    static private String addressName;

    private TempDao dao;
    static public Marker tempMarker;
    private boolean isClick;
    private boolean flag;
    private TextView tv_state;
    private TempDao tempDao;
    private FloatingActionButton fab1;
    private FloatingActionButton fab2;
    private Intent intent1;
    private ScreenListener l;
    private List<String> list = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // OSMDroid configuration
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.mv);
        tv_state = findViewById(R.id.tv_state);

        // Setup OSMDroid map
        mapView.setTileSource(TileSourceFactory.MAPNIK);
        mapView.setMultiTouchControls(true);
        mapController = mapView.getController();
        mapController.setZoom(15.0);

        // Map click listener
        MapEventsReceiver mapEventsReceiver = new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                onMapClick(p);
                return true;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                return false;
            }
        };
        mapView.getOverlays().add(new MapEventsOverlay(mapEventsReceiver));

        setUpMap();
        setUpDrawer();
        initNavigationView();

        // Legacy time service removed — MainHook reads time directly

        l = new ScreenListener(this);
        l.begin(new ScreenListener.ScreenStateListener() {
            @Override
            public void onUserPresent() {}

            @Override
            public void onScreenOn() {}

            @Override
            public void onScreenOff() {}
        });

        loadMarkers();

        SharedPreferences sp = getSharedPreferences("startTag", MODE_PRIVATE);
        String startTag = sp.getString("start", "");
        tv_state.setText(startTag);

        fabAdd();
        fabdel();

        // Refresh map markers when returning from profile editor
        getSupportFragmentManager().addOnBackStackChangedListener(new androidx.fragment.app.FragmentManager.OnBackStackChangedListener() {
            @Override
            public void onBackStackChanged() {
                if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                    loadMarkers();
                    dao = new TempDao(getApplicationContext());
                    list = dao.selectAllData();
                    tv_count.setText(String.valueOf(list.size()));
                }
            }
        });
    }

    public void setUpMap() {
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        mDrawerLayout = findViewById(R.id.drawer_layout);
        mNavView = findViewById(R.id.nav_view);
        tv_count = findViewById(R.id.count);
        fab1 = findViewById(R.id.fab1);
        fab2 = findViewById(R.id.fab2);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_map, menu);
        return super.onCreateOptionsMenu(menu);
    }

    public void loadMarkers() {
        DbHelper dbHelper = new DbHelper(getApplicationContext());
        mSQLiteDatabase = dbHelper.getWritableDatabase();
        dbHelper.onCreate(mSQLiteDatabase);

        List<GeoPoint> points = new ArrayList<>();
        Cursor cursor = mSQLiteDatabase.query(DbHelper.APP_TEMP_NAME,
                new String[]{"latitude", "longitude"}, null, null, null, null, null);

        if (cursor != null && cursor.moveToFirst()) {
            while (!cursor.isAfterLast()) {
                double lat = cursor.getDouble(cursor.getColumnIndex("latitude"));
                double lon = cursor.getDouble(cursor.getColumnIndex("longitude"));
                GeoPoint point = new GeoPoint(lat, lon);
                points.add(point);

                Marker marker = new Marker(mapView);
                marker.setPosition(point);
                marker.setDraggable(true);
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                mapView.getOverlays().add(marker);

                cursor.moveToNext();
            }
            cursor.close();

            if (!points.isEmpty()) {
                mapController.setCenter(points.get(points.size() - 1));

                if (points.size() > 1) {
                    Polyline polyline = new Polyline();
                    polyline.setPoints(points);
                    polyline.getOutlinePaint().setColor(Color.BLUE);
                    polyline.getOutlinePaint().setStrokeWidth(6f);
                    mapView.getOverlays().add(polyline);
                }
            }
        }
        mSQLiteDatabase.close();
        mapView.invalidate();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            onClear();
        } else if (id == R.id.search) {
            View view = LayoutInflater.from(this).inflate(R.layout.dialog_search, null, false);
            final EditText et_key = (EditText) view.findViewById(R.id.key);
            new AlertDialog.Builder(this).setView(view)
                    .setTitle("Search Location")
                    .setPositiveButton("Search", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            searchByCoordinates(et_key.getText().toString());
                        }
                    }).setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                }
            }).show();
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Simple coordinate search: accepts "lat,lng" format.
     * TODO: Phase 4 - Add Nominatim geocoding for address search
     */
    private void searchByCoordinates(String input) {
        try {
            String[] parts = input.split(",");
            if (parts.length == 2) {
                double lat = Double.parseDouble(parts[0].trim());
                double lng = Double.parseDouble(parts[1].trim());
                GeoPoint point = new GeoPoint(lat, lng);
                mapController.setCenter(point);
                mapController.setZoom(15.0);

                Marker marker = new Marker(mapView);
                marker.setPosition(point);
                marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
                mapView.getOverlays().add(marker);
                mapView.invalidate();
            } else {
                Toast.makeText(this, "Format: latitude,longitude (e.g. 39.9,116.4)", Toast.LENGTH_SHORT).show();
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Invalid coordinates", Toast.LENGTH_SHORT).show();
        }
    }

    public void onClear() {
        new AlertDialog.Builder(MainActivity.this)
                .setTitle("Warning")
                .setMessage("Delete all locations?")
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        tempDao = new TempDao(getApplicationContext());
                        tempDao.deleteTable();
                        tv_count.setText("0");
                        mapView.getOverlays().clear();
                        // Re-add map events overlay
                        mapView.getOverlays().add(new MapEventsOverlay(new MapEventsReceiver() {
                            @Override
                            public boolean singleTapConfirmedHelper(GeoPoint p) {
                                onMapClick(p);
                                return true;
                            }

                            @Override
                            public boolean longPressHelper(GeoPoint p) {
                                return false;
                            }
                        }));
                        onMatchIntList.clear();
                        mapView.invalidate();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    public void fabdel() {
        fab1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (tempMarker != null) {
                    mapView.getOverlays().remove(tempMarker);
                    mapView.invalidate();
                    tempMarker = null;
                }
                MyToast.setToast(getApplicationContext(), "Marker removed", 500);
            }
        });
    }

    public void fabAdd() {
        fab2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isClick) {
                    if (tempGeoPoint == null) {
                        Toast.makeText(getApplicationContext(), "Resolving coordinates, please wait...", Toast.LENGTH_SHORT).show();
                    } else {
                        double lat = tempGeoPoint.getLatitude();
                        double lon = tempGeoPoint.getLongitude();

                        // Open profile editor with lat/lon pre-filled
                        ProfileEditorFragment editor = ProfileEditorFragment.newInstance(-1, lat, lon);
                        getSupportFragmentManager().beginTransaction()
                                .replace(R.id.frame_content, editor)
                                .addToBackStack(null)
                                .commit();

                        tempGeoPoint = null;
                        addressName = null;
                        isClick = false;
                    }
                } else {
                    MyToast.setToast(getApplicationContext(), "Tap the map first!", 500);
                }
            }
        });
    }

    public void onMapClick(GeoPoint geoPoint) {
        tempGeoPoint = null;

        Marker marker = new Marker(mapView);
        marker.setPosition(geoPoint);
        marker.setDraggable(true);
        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setTitle(String.format("Lat: %.6f, Lng: %.6f", geoPoint.getLatitude(), geoPoint.getLongitude()));
        mapView.getOverlays().add(marker);
        mapView.invalidate();

        tempMarker = marker;
        tempGeoPoint = geoPoint;
        addressName = String.format("%.6f, %.6f", geoPoint.getLatitude(), geoPoint.getLongitude());

        flag = true;
        isClick = true;

        if (flag) {
            MyToast.setToast(getApplicationContext(), addressName, 500);
            flag = false;
        }
    }

    private void refreshPolyline() {
        DbHelper dbHelper = new DbHelper(getApplicationContext());
        mSQLiteDatabase = dbHelper.getWritableDatabase();
        dbHelper.onCreate(mSQLiteDatabase);

        List<GeoPoint> points = new ArrayList<>();
        Cursor cursor = mSQLiteDatabase.query(DbHelper.APP_TEMP_NAME,
                new String[]{"latitude", "longitude"}, null, null, null, null, null);

        if (cursor != null && cursor.moveToFirst()) {
            while (!cursor.isAfterLast()) {
                double lat = cursor.getDouble(cursor.getColumnIndex("latitude"));
                double lon = cursor.getDouble(cursor.getColumnIndex("longitude"));
                points.add(new GeoPoint(lat, lon));
                cursor.moveToNext();
            }
            cursor.close();
        }
        mSQLiteDatabase.close();

        // Remove old polylines
        mapView.getOverlays().removeIf(overlay -> overlay instanceof Polyline);

        if (points.size() > 1) {
            Polyline polyline = new Polyline();
            polyline.setPoints(points);
            polyline.getOutlinePaint().setColor(Color.BLUE);
            polyline.getOutlinePaint().setStrokeWidth(6f);
            mapView.getOverlays().add(polyline);
        }
        mapView.invalidate();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mapView.onDetach();
        l.unregisterListener();
    }

    private void initNavigationView() {
        ImageView icon = (ImageView) mNavView.getHeaderView(0).findViewById(R.id.nav_head_icon);
        icon.setImageResource(R.drawable.nav_head_icon);
        TextView name = (TextView) mNavView.getHeaderView(0).findViewById(R.id.nav_head_name);
        name.setText(R.string.app_name);
        mNavView.setNavigationItemSelectedListener(new NavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.nav_item1) {
                    dao = new TempDao(getApplicationContext());
                    List<String> list = dao.selectAllData();
                    tv_count.setText(String.valueOf(list.size()));
                    loadMarkers();
                    getSupportFragmentManager().beginTransaction()
                            .replace(R.id.frame_content, new OneFragment()).commit();
                    invalidateOptionsMenu();
                } else if (id == R.id.nav_item2) {
                    toolbar.setTitle(R.string.Collection);
                    getSupportFragmentManager().beginTransaction()
                            .replace(R.id.frame_content, new CollectionFragment()).commit();
                    invalidateOptionsMenu();
                } else if (id == R.id.nav_set) {
                    toolbar.setTitle(R.string.setting);
                    getSupportFragmentManager().beginTransaction()
                            .replace(R.id.frame_content, new SettingFragment()).commit();
                    invalidateOptionsMenu();
                } else if (id == R.id.menu_share) {
                    toolbar.setTitle(R.string.help);
                    getSupportFragmentManager().beginTransaction()
                            .replace(R.id.frame_content, new HelpFragment()).commit();
                    invalidateOptionsMenu();
                } else if (id == R.id.nav_about) {
                    toolbar.setTitle(R.string.about);
                }
                invalidateOptionsMenu();
                item.setChecked(true);
                mDrawerLayout.closeDrawers();
                return true;
            }
        });
    }

    private void setUpDrawer() {
        setSupportActionBar(toolbar);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, mDrawerLayout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        mDrawerLayout.addDrawerListener(toggle);
        toggle.syncState();
    }

    @Override
    public void onBackPressed() {
        mDrawerLayout = findViewById(R.id.drawer_layout);
        if (mDrawerLayout.isDrawerOpen(GravityCompat.START)) {
            mDrawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void sendMessageValue(boolean booleanValue) {
        if (booleanValue) {
            tv_state.setText("Location spoofing active");
            SharedPreferences sp = getSharedPreferences("startTag", MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            editor.putString("start", "Location spoofing active");
            editor.apply();
        }
    }
}
