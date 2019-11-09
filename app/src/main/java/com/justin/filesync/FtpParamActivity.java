package com.justin.filesync;

import android.content.SharedPreferences;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.view.View;
import android.widget.EditText;

public class FtpParamActivity extends AppCompatActivity {

    EditText edtFtpAddr;
    EditText edtPort;
    EditText edtUser;
    EditText edtPasswd;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ftp_param);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);


        SharedPreferences sp=this.getPreferences(MODE_PRIVATE);
        String strFtpAddr = sp.getString("FtpAddr", "");
        int iPort = sp.getInt("FtpPort",22);
        String strUserName = sp.getString("UserName", "");
        String strPassword = sp.getString("Password", "");

        edtFtpAddr = findViewById(R.id.edtFtpAddr);
        edtPort = findViewById(R.id.edtFtpPort);
        edtUser = findViewById(R.id.edtUserName);
        edtPasswd = findViewById(R.id.edtPassword);

        edtFtpAddr.setText(strFtpAddr);
        edtPort.setText(Integer.toString(iPort));
        edtUser.setText(strUserName);
        edtPasswd.setText(strPassword);
    }

    @Override
    protected void onStop() {

        String strAddr = edtFtpAddr.getText().toString();
        String strUserName = edtUser.getText().toString();
        String strPasswd = edtPasswd.getText().toString();
        int port = Integer.parseInt(edtPort.getText().toString());


        SharedPreferences sp=this.getPreferences(MODE_PRIVATE);
        SharedPreferences.Editor editor=sp.edit();
        editor.putString("FtpAddr", strAddr);
        editor.putInt("FtpPort",port);
        editor.putString("UserName", strUserName);
        editor.putString("Password", strPasswd);

        editor.commit();

        super.onStop();
    }
}
