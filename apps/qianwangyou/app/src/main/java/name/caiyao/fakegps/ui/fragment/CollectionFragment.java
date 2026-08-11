package name.caiyao.fakegps.ui.fragment;


import android.app.Activity;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import name.caiyao.fakegps.R;
import name.caiyao.fakegps.dao.ProfileDao;
import name.caiyao.fakegps.dao.TempDao;
import name.caiyao.fakegps.data.DbHelper;

public class CollectionFragment extends Fragment {

    private FragmentActivity mfragment;
    private Toolbar toolbar;
    private ListView listview;
    private TempDao tempDao;
    private ProfileDao profileDao;
    private ArrayAdapter<String> adapter;
    private List<String> mList = new ArrayList<String>();
    private List<ProfileDao.ProfileSummary> profileList = new ArrayList<>();
    private Button save;
    private Button clearall;
    private CalbackValue mCalbackValue;

    private void initView(View view) {

        listview = (ListView) view.findViewById(R.id.listview);
        save = (Button) view.findViewById(R.id.save);
        clearall = (Button) view.findViewById(R.id.clearall);

    }

    private List<String> initData() {
        profileDao = new ProfileDao(new DbHelper(mfragment));
        profileList = profileDao.listProfiles();
        mList.clear();
        for (ProfileDao.ProfileSummary p : profileList) {
            String display = p.name.isEmpty()
                    ? String.format("%.6f, %.6f", p.latitude, p.longitude)
                    : p.name;
            mList.add(display);
        }
        return mList;
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        setHasOptionsMenu(true);
        mfragment = getActivity();
        initView(view);
        toolbar = new Toolbar(mfragment);
        adapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_list_item_1, initData());
        listview.setAdapter(adapter);

        save.setText("模拟位置开始("+mList.size()+")");

        // Short click -> open editor
        listview.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, final int position, long l) {
                if (position < profileList.size()) {
                    ProfileDao.ProfileSummary p = profileList.get(position);
                    ProfileEditorFragment editor = ProfileEditorFragment.newInstance(
                            p.id, p.latitude, p.longitude);
                    getParentFragmentManager().beginTransaction()
                            .replace(R.id.frame_content, editor)
                            .addToBackStack(null)
                            .commit();
                }
            }
        });

        // Long click -> delete
        listview.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, final int position, long id) {
                if (position >= profileList.size()) return false;
                new AlertDialog.Builder(mfragment)
                        .setTitle("提示")
                        .setMessage("确定要删除位置？")
                        .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                ProfileDao.ProfileSummary p = profileList.get(position);
                                profileDao.deleteProfile(p.id);
                                profileList.remove(position);
                                mList.remove(position);
                                adapter.notifyDataSetChanged();
                                save.setText("模拟位置开始("+mList.size()+")");
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            }
        });

        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (mList.size() == 0) {
                    Toast.makeText(getActivity(), "你没有选点", Toast.LENGTH_SHORT).show();
                    return;
                } else if (mList.size() >7) {

                    AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
                    builder.setIcon(R.mipmap.ic_launcher);
                    builder.setTitle("模拟位置开始");
                    builder.setMessage("位置会随着时间早上8点开始");
                    builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {

                            mCalbackValue.sendMessageValue(true);

                            Toast.makeText(getActivity(), "模拟位置开始,清除微信后台重进", Toast.LENGTH_LONG).show();
                        }
                    }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {

                        }
                    }).show();
                } else {
                    Toast.makeText(getActivity(), "选择8个地点以上", Toast.LENGTH_SHORT).show();
                }
            }
        });


        clearall.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {

                clearall();
            }
        });

    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh list when returning from editor
        if (profileDao != null && adapter != null) {
            initData();
            adapter.notifyDataSetChanged();
            save.setText("模拟位置开始("+mList.size()+")");
        }
    }

    public void clearall() {
        new AlertDialog.Builder(mfragment).setTitle("提示").setMessage("确定要删除所有位置吗？").setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                tempDao = new TempDao(mfragment);
                tempDao.deleteTable();
                mList.clear();
                profileList.clear();
                adapter.notifyDataSetChanged();

                save.setText("模拟位置开始("+mList.size()+")");
            }
        }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {

            }
        }).show();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.frament_content, container, false);
        return view;
    }


    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.menu_parent_fragment, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_clear) {
            clearall();
        }
        return true;
    }


    /**
     * fragment与activity产生关联是  回调这个方法
     */

    @Override
    public void onAttach(Activity activity) {

        super.onAttach(activity);

       mCalbackValue =(CalbackValue)getActivity();
    }


    /**
     * 定义一个回调接口
     */

    public interface CalbackValue{

        public  void sendMessageValue(boolean booleanValue);
    }

}