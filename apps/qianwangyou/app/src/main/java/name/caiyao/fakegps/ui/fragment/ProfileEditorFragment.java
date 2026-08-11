package name.caiyao.fakegps.ui.fragment;

import android.content.ContentValues;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import name.caiyao.fakegps.R;
import name.caiyao.fakegps.dao.ProfileDao;
import name.caiyao.fakegps.data.DbHelper;
import name.caiyao.fakegps.ui.profile.FieldSpec;

public class ProfileEditorFragment extends Fragment {

    private static final String ARG_PROFILE_ID = "profile_id";
    private static final String ARG_LATITUDE = "latitude";
    private static final String ARG_LONGITUDE = "longitude";

    private long profileId = -1;
    private ProfileDao profileDao;

    /** In-memory field values. Only active (non-null) columns are written to DB. */
    private final ContentValues workingValues = new ContentValues();
    /** Tracks which columns the user has explicitly set. */
    private final Set<String> activeColumns = new HashSet<>();

    /** Badge TextViews per category, keyed by category name. */
    private final LinkedHashMap<String, TextView> badgeViews = new LinkedHashMap<>();
    /** Field counts per category for badge update. */
    private final LinkedHashMap<String, int[]> categoryCounts = new LinkedHashMap<>();

    public static ProfileEditorFragment newInstance(long profileId, double lat, double lon) {
        ProfileEditorFragment f = new ProfileEditorFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_PROFILE_ID, profileId);
        args.putDouble(ARG_LATITUDE, lat);
        args.putDouble(ARG_LONGITUDE, lon);
        f.setArguments(args);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        profileDao = new ProfileDao(new DbHelper(requireContext()));

        Bundle args = getArguments();
        if (args != null) {
            profileId = args.getLong(ARG_PROFILE_ID, -1);
        }

        // Load existing profile if editing
        if (profileId > 0) {
            ContentValues existing = profileDao.loadProfile(profileId);
            workingValues.putAll(existing);
            for (String key : existing.keySet()) {
                // Exclude addname from activeColumns - it's auto-generated on save
                if (!"addname".equals(key)) {
                    activeColumns.add(key);
                }
            }
        } else if (args != null) {
            // New profile from map tap - pre-fill lat/lon
            double lat = args.getDouble(ARG_LATITUDE, 0);
            double lon = args.getDouble(ARG_LONGITUDE, 0);
            if (lat != 0 || lon != 0) {
                workingValues.put("latitude", lat);
                workingValues.put("longitude", lon);
                activeColumns.add("latitude");
                activeColumns.add("longitude");
            }
        }

        LinearLayout container2 = view.findViewById(R.id.categoriesContainer);
        buildCategoryCards(container2);

        // Save FAB
        view.findViewById(R.id.fabSave).setOnClickListener(v -> save());
    }

    private void buildCategoryCards(LinearLayout container) {
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        LinkedHashMap<String, List<FieldSpec>> categories = FieldSpec.allCategories();
        boolean isFirstLocation = true;

        for (Map.Entry<String, List<FieldSpec>> entry : categories.entrySet()) {
            String catName = entry.getKey();
            List<FieldSpec> fields = entry.getValue();

            View card = inflater.inflate(R.layout.item_profile_category, container, false);
            TextView title = card.findViewById(R.id.categoryTitle);
            TextView badge = card.findViewById(R.id.categoryBadge);
            ImageView arrow = card.findViewById(R.id.expandArrow);
            LinearLayout fieldsContainer = card.findViewById(R.id.fieldsContainer);
            View header = card.findViewById(R.id.categoryHeader);

            title.setText(catName);
            badgeViews.put(catName, badge);
            categoryCounts.put(catName, new int[]{0, fields.size()});

            // Toggle collapse
            header.setOnClickListener(v -> {
                boolean visible = fieldsContainer.getVisibility() == View.VISIBLE;
                fieldsContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
                arrow.setRotation(visible ? 0f : 180f);
            });

            // Build field rows
            int activeInCategory = 0;
            for (FieldSpec spec : fields) {
                View fieldView;
                if (spec.type == FieldSpec.FieldType.BOOLEAN) {
                    fieldView = buildBooleanField(inflater, fieldsContainer, spec, catName);
                } else {
                    fieldView = buildTextField(inflater, fieldsContainer, spec, catName);
                }
                fieldsContainer.addView(fieldView);
                if (activeColumns.contains(spec.dbColumn)) activeInCategory++;
            }

            categoryCounts.get(catName)[0] = activeInCategory;
            updateBadge(catName);

            // Auto-expand Location card if has lat/lon
            if (isFirstLocation && "Location".equals(catName) && activeInCategory > 0) {
                fieldsContainer.setVisibility(View.VISIBLE);
                arrow.setRotation(180f);
            }
            isFirstLocation = false;

            container.addView(card);
        }
    }

    private View buildTextField(LayoutInflater inflater, ViewGroup parent,
                                FieldSpec spec, String catName) {
        View v = inflater.inflate(R.layout.item_field_text, parent, false);
        TextInputLayout layout = v.findViewById(R.id.fieldInputLayout);
        TextInputEditText editText = v.findViewById(R.id.fieldEditText);

        String label = spec.displayName;
        if (spec.unit != null) label += " (" + spec.unit + ")";
        layout.setHint(label);
        editText.setHint(spec.hint);

        // Set input type based on field type
        switch (spec.type) {
            case INTEGER:
                editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
                break;
            case DOUBLE:
            case FLOAT:
                editText.setInputType(InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_SIGNED
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                break;
            default:
                editText.setInputType(InputType.TYPE_CLASS_TEXT);
                break;
        }

        // Pre-fill existing value
        if (activeColumns.contains(spec.dbColumn)) {
            Object val = workingValues.get(spec.dbColumn);
            if (val != null) editText.setText(String.valueOf(val));
        }

        // Watch edits
        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                String text = s.toString().trim();
                if (text.isEmpty()) {
                    activeColumns.remove(spec.dbColumn);
                    workingValues.remove(spec.dbColumn);
                } else {
                    activeColumns.add(spec.dbColumn);
                    putTypedValue(spec, text);
                }
                recountCategory(catName);
            }
        });

        return v;
    }

    private View buildBooleanField(LayoutInflater inflater, ViewGroup parent,
                                   FieldSpec spec, String catName) {
        View v = inflater.inflate(R.layout.item_field_boolean, parent, false);
        TextView label = v.findViewById(R.id.boolLabel);
        Spinner spinner = v.findViewById(R.id.boolSpinner);

        label.setText(spec.displayName);

        String[] options = {
                getString(R.string.bool_passthrough),
                getString(R.string.bool_yes),
                getString(R.string.bool_no)
        };
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        // Pre-fill
        if (activeColumns.contains(spec.dbColumn)) {
            Object val = workingValues.get(spec.dbColumn);
            if (val instanceof Integer || val instanceof Long) {
                long v2 = val instanceof Integer ? (Integer) val : (Long) val;
                spinner.setSelection(v2 != 0 ? 1 : 2);
            }
        }

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean init = true;

            @Override
            public void onItemSelected(AdapterView<?> p, View view, int pos, long id) {
                if (init) { init = false; return; }
                if (pos == 0) {
                    // Passthrough
                    activeColumns.remove(spec.dbColumn);
                    workingValues.remove(spec.dbColumn);
                } else {
                    activeColumns.add(spec.dbColumn);
                    workingValues.put(spec.dbColumn, pos == 1 ? 1 : 0);
                }
                recountCategory(catName);
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {}
        });

        return v;
    }

    private void putTypedValue(FieldSpec spec, String text) {
        try {
            switch (spec.type) {
                case INTEGER:
                    workingValues.put(spec.dbColumn, Long.parseLong(text));
                    break;
                case DOUBLE:
                    workingValues.put(spec.dbColumn, Double.parseDouble(text));
                    break;
                case FLOAT:
                    workingValues.put(spec.dbColumn, Float.parseFloat(text));
                    break;
                default:
                    workingValues.put(spec.dbColumn, text);
                    break;
            }
        } catch (NumberFormatException e) {
            // Invalid number: remove from active columns so it won't be saved
            workingValues.remove(spec.dbColumn);
            activeColumns.remove(spec.dbColumn);
        }
    }

    private void recountCategory(String catName) {
        int[] counts = categoryCounts.get(catName);
        if (counts == null) return;
        LinkedHashMap<String, List<FieldSpec>> categories = FieldSpec.allCategories();
        List<FieldSpec> fields = categories.get(catName);
        if (fields == null) return;

        int active = 0;
        for (FieldSpec spec : fields) {
            if (activeColumns.contains(spec.dbColumn)) active++;
        }
        counts[0] = active;
        updateBadge(catName);
    }

    private void updateBadge(String catName) {
        TextView badge = badgeViews.get(catName);
        int[] counts = categoryCounts.get(catName);
        if (badge == null || counts == null) return;
        if (counts[0] > 0) {
            badge.setText(counts[0] + "/" + counts[1]);
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    private void save() {
        // Build final ContentValues: only active columns
        ContentValues cv = new ContentValues();
        for (String col : activeColumns) {
            Object val = workingValues.get(col);
            if (val instanceof String) cv.put(col, (String) val);
            else if (val instanceof Long) cv.put(col, (Long) val);
            else if (val instanceof Integer) cv.put(col, (Integer) val);
            else if (val instanceof Double) cv.put(col, (Double) val);
            else if (val instanceof Float) cv.put(col, (Float) val);
        }

        // Always regenerate addname from current lat/lon
        Double lat = cv.getAsDouble("latitude");
        Double lon = cv.getAsDouble("longitude");
        if (lat != null && lon != null) {
            cv.put("addname", String.format("%.6f, %.6f", lat, lon));
        }

        long savedProfileId;
        if (profileId > 0) {
            profileDao.updateProfile(profileId, cv);
            savedProfileId = profileId;
        } else {
            savedProfileId = profileDao.insertProfile(cv);
        }

        // Re-publish the effective config to world-readable prefs so the hook picks up the change.
        name.caiyao.fakegps.config.ConfigPrefsSync.sync(
                requireContext().getApplicationContext(), savedProfileId);

        Toast.makeText(requireContext(), "Saved", Toast.LENGTH_SHORT).show();

        // Pop back
        if (getParentFragmentManager().getBackStackEntryCount() > 0) {
            getParentFragmentManager().popBackStack();
        }
    }
}
