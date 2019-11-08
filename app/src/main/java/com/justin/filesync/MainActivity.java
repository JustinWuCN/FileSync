package com.justin.filesync;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;


public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    static final String TAG = "MainActivity";

    static FTPCommunication mFtp;

	//存储服务器的地址，登录名，密码。
	//多个线程一起上传数据时，使用同一个地址，所以在启动同步前，先要从SharePerference里读出。
	static String mStrFtpAddr;
	static String mStrUserName;
	static String mStrPasswd;

    private Context mContext;

    private static final String[] STORAGE_PERMISSIONS = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mContext = this;
        Button btn1 = (Button) findViewById(R.id.btnUpload);
        btn1.setOnClickListener(this);
        Button btn2 = (Button) findViewById(R.id.btnSelectFolder);
        btn2.setOnClickListener(this);

        verifyPermissions();

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        Intent intent;

        //noinspection SimplifiableIfStatement
        switch (id)
        {
            case  R.id.action_ftpparam:
                intent = new Intent(this, FtpParamActivity.class);
                startActivity(intent);
                return true;
            case R.id.action_SyncDir:
                intent = new Intent(this, FoldSelectActivity.class);
                startActivity(intent);
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void verifyPermissions() {
        Log.d(TAG, "verifyPermissions: Checking Permissions.");


        int permissionExternalMemory = ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE);


        if (permissionExternalMemory != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    MainActivity.this,
                    STORAGE_PERMISSIONS,
                    1
            );
        }
    }

    private boolean checkPermission() {
        int result = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
        if (result == PackageManager.PERMISSION_GRANTED) {

            return true;

        } else {

            return false;

        }
    }

    //implement the onClick method here
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.btnUpload:
                UploadFile();
                break;
            case R.id.btnSelectFolder:
                OpenSelectFolder();
                break;
            default:
        }
    }


    /**
     * A simple array adapter that creates a list of cheeses.
     */
    private class MyAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return Cheeses.CHEESES.length;
        }

        @Override
        public String getItem(int position) {
            return Cheeses.CHEESES[position];
        }

        @Override
        public long getItemId(int position) {
            return Cheeses.CHEESES[position].hashCode();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup container) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.list_item, container, false);
            }

            ((TextView) convertView.findViewById(android.R.id.text1))
                    .setText(getItem(position));
            return convertView;
        }
    }

    //同步完成后,通知上层,上层断开连接.
    class SyncCallback implements FTPCommunication.Callback {

        @Override
        public void SyncComplete(FTPCommunication owner, int isComplete) {
            owner.disconnect();
        }
    }

    ;

    //开新线程进行网络操作
    class MyThread extends Thread {
        public void start() {
            Log.e(TAG, "Justin. MyThread in.");
            String SdcardRootPath = Environment.getExternalStorageDirectory().getAbsolutePath();
            //mFtp = new FTPCommunication(mContext, SdcardRootPath + "//meizu//", "//ftp//", "192.168.31.122", "anonymous", "anonymous");
            mFtp = new FTPCommunication(mContext, SdcardRootPath + "//meizu//", "//ftp//", mStrFtpAddr, mStrUserName, mStrPasswd);

            mFtp.setCallback(new SyncCallback());

            mFtp.StartSync();

        }
    }

    ;

    @Override
    protected void onDestroy() {
        mFtp.disconnect();

        super.onDestroy();
    }

	protected void ReadPreference() {
        SharedPreferences sp=this.getPreferences(MODE_PRIVATE);
        mStrFtpAddr = sp.getString("FtpAddr", "");
        //int iPort = sp.getInt("FtpPort",20);
        mStrUserName = sp.getString("UserName", "");
        mStrPasswd = sp.getString("Password", "");
	}

    protected void UploadFile() {
        Log.e(TAG, "Justin. button in.");
/*
        String SdcardRootPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        File f = new File(SdcardRootPath + "//Movies");//strPath为路径
        File[] fSingle = f.listFiles();
        fSingle[1].isDirectory();

        String[] str = f.list();//String[]

        for(int i = 0; i < str.length; ++i) {
            Log.d(TAG, str[i]);
        }
*/
		ReadPreference();
        new MyThread().start();
        //testOpenFile();
    }

    protected void testOpenFile() {
        String SdcardRootPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        //Log.e(TAG, "sdcard path = " + mSdcardRootPath);
        String path = SdcardRootPath + "//ceshitest.txt";
        File file = new File(path);
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            inputStream.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
//	private Dialog createFTPLoginDialog() {
//
//		View rootLoadView = getLayoutInflater().inflate(R.layout.dialog_ftp_login,
//				null);
//		final EditText editHost = (EditText) rootLoadView.findViewById(R.id.editFTPHost);
//		final EditText editPort= (EditText) rootLoadView.findViewById(R.id.editFTPPort);
//		editPort.setText("2121");
//		editHost.setText("10.2.20.153");
//		final EditText editUser = (EditText) rootLoadView.findViewById(R.id.editFTPUser);
//		final EditText editPasword= (EditText) rootLoadView.findViewById(R.id.editPassword);
//		editUser.setText("ftpuser");
//		editPasword.setText("123456");
//		return new AlertDialog.Builder(this)
//				.setTitle("请输入FTP信息")
//				.setView(rootLoadView)
//				.setPositiveButton("连接FTP", new DialogInterface.OnClickListener() {
//
//					@Override
//					public void onClick(DialogInterface uploadDialog, int which) {
//						
//						if (TextUtils.isEmpty(editHost.getText()) || 
//								TextUtils.isEmpty(editPort.getText()) || 
//								TextUtils.isEmpty(editUser.getText()) ||
//								TextUtils.isEmpty(editUser.getText())) {
//							  toast("请将资料填写完整");
//							  FtpMainActivity.this.finish();
//							  return ;
//						}
//						try{
//						    mFTPPort = Integer.parseInt(editPort.getText().toString().trim());
//						}
//						catch(NumberFormatException nfEx){
//							nfEx.printStackTrace();
//							toast("端口输入有误，请重试");
//							return ;
//						}
////						mFTPHost = editHost.getText().toString().trim();
////						mFTPUser = editUser.getText().toString().trim();
////						mFTPPassword = editPasword.getText().toString().trim();
////						Log.v(TAG, "mFTPHost #" + mFTPHost + " mFTPPort #" + mFTPPort 
////								+ " mFTPUser #" + mFTPUser + " mFTPPassword #" + mFTPPassword);
////						executeConnectRequest();
//					}
//				}).create();
//	}
//
//	//文件选择器相关功能实现
//	private void openFileDialog() {
//		initDialog();
//		uploadDialog = new AlertDialog.Builder(this).create();
//		Window window = uploadDialog.getWindow();
//		WindowManager.LayoutParams lp = window.getAttributes();
//		window.setAttributes(lp);
//		uploadDialog.show();
//		uploadDialog.getWindow().setContentView(fileChooserView,
//				new RelativeLayout.LayoutParams(400, 640));
//	}
//}

    private String OpenSelectFolder() {
        final String[] chosen = new String[1];

        /////////////////////////////////////////////////////////////////////////////////////////////////
        //Create FileOpenDialog and register a callback
        /////////////////////////////////////////////////////////////////////////////////////////////////
        SimpleFileDialog FolderChooseDialog = new SimpleFileDialog(MainActivity.this, "FolderChoose",
                new SimpleFileDialog.SimpleFileDialogListener() {
                    @Override
                    public void onChosenDir(String chosenDir) {
                        // The code in this function will be executed when the dialog OK button is pushed
                        chosen[0] = chosenDir;
                        Toast.makeText(MainActivity.this, "Chosen FileOpenDialog File: " +
                                chosen[0], Toast.LENGTH_LONG).show();
                    }
                });

        FolderChooseDialog.chooseFile_or_Dir();

        return chosen[0];

    }

}
