package name.caiyao.fakegps.ui.fragment;

import android.os.Bundle;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.ListFragment;
import androidx.appcompat.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import name.caiyao.fakegps.R;

public class SettingFragment extends Fragment {

    Toolbar toolbar;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        toolbar = new Toolbar(getActivity());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.setting, container, false);
        return view;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        Log.i("520it", "SettingFragment" + "*************  onCreateOptionsMenu  *************");
        menu.clear();
        inflater.inflate(R.menu.menu_parent_fragment, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        return true;
    }

}
