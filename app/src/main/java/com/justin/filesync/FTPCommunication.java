package com.justin.filesync;

//FTPCommunication类
// 实现FTP通信.包括FTP服务器连接,读取文件列表,切换目录.上传文件.
// 实现回调函数,提示上层请求完成.

import it.sauronsoftware.ftp4j.FTPAbortedException;
import it.sauronsoftware.ftp4j.FTPClient;
import it.sauronsoftware.ftp4j.FTPDataTransferException;
import it.sauronsoftware.ftp4j.FTPDataTransferListener;
import it.sauronsoftware.ftp4j.FTPException;
import it.sauronsoftware.ftp4j.FTPFile;
import it.sauronsoftware.ftp4j.FTPIllegalReplyException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import android.content.Context;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import static java.lang.Thread.sleep;

public class FTPCommunication {

	private static String TAG = "FTPCommunication";

	private CmdFactory mCmdFactory;
	private FTPClient mFTPClient;
	private ExecutorService mThreadPool;

	private static String mAtSDCardPath;


	private List<FTPFile> mFileList = new ArrayList<FTPFile>();
	private Object mLock = new Object();
	private int mSelectedPosistion = -1;

	private String mCurrentPWD; // 当前远程目录
	private static final String OLIVE_DIR_NAME = "OliveDownload";

	// Upload
	private String mSdcardRootPath;
	private String mLastFilePath;


	private Thread mDameonThread = null ;
	private boolean mDameonRunning = true;
	
	private String mFTPHost ;
	private int mFTPPort  = 21;
	private String mFTPUser ;
	private String mFTPPassword ;
	
	private static final int MAX_THREAD_NUMBER = 5;
	private static final int MAX_DAMEON_TIME_WAIT = 2 * 1000; // millisecond

	private static final int MENU_OPTIONS_BASE = 0;
	private static final int MSG_CMD_CONNECT_OK = MENU_OPTIONS_BASE + 1;
	private static final int MSG_CMD_CONNECT_FAILED = MENU_OPTIONS_BASE + 2;
	private static final int MSG_CMD_LIST_OK = MENU_OPTIONS_BASE + 3;
	private static final int MSG_CMD_LIST_FAILED = MENU_OPTIONS_BASE + 4;
	private static final int MSG_CMD_CWD_OK = MENU_OPTIONS_BASE + 5;
	private static final int MSG_CMD_CWD_FAILED = MENU_OPTIONS_BASE + 6;
	private static final int MSG_CMD_DELE_OK = MENU_OPTIONS_BASE + 7;
	private static final int MSG_CMD_DELE_FAILED = MENU_OPTIONS_BASE + 8;
	private static final int MSG_CMD_RENAME_OK = MENU_OPTIONS_BASE + 9;
	private static final int MSG_CMD_RENAME_FAILED = MENU_OPTIONS_BASE + 10;

	private static final int MSG_CMD_UPLOAD_SUCCESS = MENU_OPTIONS_BASE + 11;
	private static final int MSG_CMD_UPLOAD_FAILED = MENU_OPTIONS_BASE + 12;


	private static final int MSG_CMD_SYNC_OK = MENU_OPTIONS_BASE + 13;

	//需要通过一个结构体,通知上层
	class CommandResult {
		public static final int CMD_CWD = 1;
		public static final int CMD_UPLOAD = 2;
		public static final int CMD_SYNC = 3;

		public int cmd;		//当前执行的命令,
		public String strValue;	//命令的参数.
		public int result;		//返回的状态.
	}

	private CommandResult commandResult = new CommandResult();

	private String mDirectory;
	private String mDestPath;
	private int mConnectTry = 0;	//重试连接次数
	private Context mContext;	//为了Toast函数能够工作,需要将上层的Context传进来.

	private boolean mBusy = false;	//如果当前正在工作,不能接收下一个命令.

	private boolean mExitSync = false;

	//类主入口。
	public  FTPCommunication(Context context, String strDirectory, String strDest, String server, String user, String passwd)
	{
		mContext = context;
	    mDirectory = strDirectory;
	    mDestPath = strDest;
	    mFTPHost = server;
	    mFTPUser = user;
	    mFTPPassword = passwd;

		mSdcardRootPath = Environment.getExternalStorageDirectory().getAbsolutePath();
		Log.e(TAG, "sdcard path = " + mSdcardRootPath);
		mCmdFactory = new CmdFactory();
		mFTPClient = new FTPClient();
		mThreadPool = Executors.newFixedThreadPool(MAX_THREAD_NUMBER);

		//创建连接。
	    //executeConnectRequest();
	}

	public void disconnect()
	{
		mExitSync = true;	//如果连不上服务器,需要通过这个变量来退出同步线程.
		mDameonRunning = false ;
		Thread thread = new Thread(mCmdFactory.createCmdDisConnect()) ;
		thread.start();
		//等待连接中断
		try {
			thread.join(2000);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		mThreadPool.shutdownNow();
	}

	//回调函数接口.
    public interface Callback {
        /**
         * 上层操作的完成情况 
         */
		void SyncComplete(FTPCommunication owner, int isComplete);
    }

    private Callback mCallback;

    public void setCallback(Callback callback) {
        this.mCallback = callback;
    }

	//切换到指定目录.
	public boolean SwitchDirectory(String strDir) {
    	if(mBusy)
    		return false;

		//暂存命令参数,后面会填上执行结果
		commandResult.cmd = CommandResult.CMD_CWD;
		commandResult.strValue = strDir;
		executeCWDRequest(strDir);

		return false;
	}

	//上传一个文件.
	public boolean UploadFile(String strFile) {
		if(mBusy)
			return false;

		//暂存命令参数,后面会填上执行结果
		commandResult.cmd = CommandResult.CMD_UPLOAD;
		commandResult.strValue = strFile;

		//先查询服务器上有没有这个文件.
    	new CmdUpload().execute(strFile);
    	return true;
	}

	private void ReportMessage(int cmd, int result) {
    	if(mCallback == null)
    		return;

    	if(cmd == CommandResult.CMD_SYNC)
    		mCallback.SyncComplete(this, result);
	}

//==================================================================================
	private Handler mHandler = new Handler() {

		@Override
		public void handleMessage(Message msg) {
			logv("mHandler --->" + msg.what);
			switch (msg.what) {
			case MSG_CMD_CONNECT_OK:
				toast("FTP服务器连接成功");
				if(mDameonThread == null){
					//启动守护进程。
					mDameonThread = new Thread(new DameonFtpConnector());
					mDameonThread.setDaemon(true);
					mDameonThread.start();
				}
				//executeLISTRequest();
				//上传一个文件
				//new CmdUpload().execute(mDirectory + "//ceshitest.txt");
				break;
			case MSG_CMD_CONNECT_FAILED:
				toast("FTP服务器连接失败，正在重新连接");
				//重试10次.
				++mConnectTry;
				if(mConnectTry < 10)
				    executeConnectRequest();
				break;
			case MSG_CMD_LIST_OK:
				toast("请求数据成功。");
				//buildOrUpdateDataset();
				break;
			case MSG_CMD_LIST_FAILED:
				toast("请求数据失败。MSG_CMD_LIST_FAILED");
				break;
			case MSG_CMD_CWD_OK:
				toast("请求数据成功。");
				executeLISTRequest();
				break;
			case MSG_CMD_CWD_FAILED:
				toast("请求数据失败。MSG_CMD_CWD_FAILED");
				break;
			case MSG_CMD_DELE_OK:
				toast("请求数据成功。");
				executeLISTRequest();
				break;
			case MSG_CMD_DELE_FAILED:
				toast("请求数据失败。MSG_CMD_DELE_FAILED");
				break;
			case MSG_CMD_RENAME_OK:
				toast("请求数据成功。");
				executeLISTRequest();
				break;
			case MSG_CMD_RENAME_FAILED:
				toast("请求数据失败。MSG_CMD_RENAME_FAILED");
				break;
			case MSG_CMD_SYNC_OK:

				break;
			default:
				break;
			}
		}
	};


	private void executeConnectRequest() {
		mThreadPool.execute(mCmdFactory.createCmdConnect());
	}

	private void executeDisConnectRequest() {
		mThreadPool.execute(mCmdFactory.createCmdDisConnect());
	}

	private void executePWDRequest() {
		mThreadPool.execute(mCmdFactory.createCmdPWD());
	}

	private void executeLISTRequest() {
		mThreadPool.execute(mCmdFactory.createCmdLIST());
	}

	private void executeCWDRequest(String path) {
		mThreadPool.execute(mCmdFactory.createCmdCWD(path));
	}

	private void executeDELERequest(String path, boolean isDirectory) {
		mThreadPool.execute(mCmdFactory.createCmdDEL(path, isDirectory));
	}

	private void executeREANMERequest(String newPath) {
		mThreadPool.execute(mCmdFactory.createCmdRENAME(newPath));
	}

	private void logv(String log) {
		Log.v(TAG, log);
	}

	private void toast(String hint) {
		Toast.makeText(mContext, hint, Toast.LENGTH_SHORT).show();
	}
    


	private File[] folderScan(String path) {
		File file = new File(path);
		File[] files = file.listFiles();
		return files;
	}


	public class CmdFactory {

		public FtpCmd createCmdConnect() {
			return new CmdConnect();
		}

		public FtpCmd createCmdDisConnect() {
			return new CmdDisConnect();
		}

		public FtpCmd createCmdPWD() {
			return new CmdPWD();
		}

		public FtpCmd createCmdLIST() {
			return new CmdLIST();
		}

		public FtpCmd createCmdCWD(String path) {
			return new CmdCWD(path);
		}

		public FtpCmd createCmdDEL(String path, boolean isDirectory) {
			return new CmdDELE(path, isDirectory);
		}

		public FtpCmd createCmdRENAME(String newPath) {
			return new CmdRENAME(newPath);
		}
	}

	public class DameonFtpConnector implements Runnable {

		@Override
		public void run() {
			Log.v(TAG, "DameonFtpConnector ### run");
			while (mDameonRunning) {
				if (mFTPClient != null && !mFTPClient.isConnected()) {
					try {
						mFTPClient.connect(mFTPHost, mFTPPort);
						mFTPClient.login(mFTPUser, mFTPPassword);
					} catch (Exception ex) {
						ex.printStackTrace();
					}
				}
				try {
					sleep(MAX_DAMEON_TIME_WAIT);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
	}

	public abstract class FtpCmd implements Runnable {

		public abstract void run();

	}

	public class CmdConnect extends FtpCmd {
		@Override
		public void run() {
			boolean errorAndRetry = false ;  //根据不同的异常类型，是否重新捕获
			try {
				String[] welcome = mFTPClient.connect(mFTPHost, mFTPPort);
				if (welcome != null) {
					for (String value : welcome) {
						logv("connect " + value);
					}
				}
				mFTPClient.login(mFTPUser, mFTPPassword);
				mHandler.sendEmptyMessage(MSG_CMD_CONNECT_OK);
			}catch (IllegalStateException illegalEx) {
				illegalEx.printStackTrace();
				errorAndRetry = true ;
			}catch (IOException ex) {
				ex.printStackTrace();
				errorAndRetry = true ;
			}catch (FTPIllegalReplyException e) {
				e.printStackTrace();
			}catch (FTPException e) {
				e.printStackTrace();
				errorAndRetry = true ;
			}
			if(errorAndRetry && mDameonRunning){
				mHandler.sendEmptyMessageDelayed(MSG_CMD_CONNECT_FAILED, 2000);
			}
		}
	}

	public class CmdDisConnect extends FtpCmd {

		@Override
		public void run() {
			if (mFTPClient != null) {
				try {
					mFTPClient.disconnect(true);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		}
	}

	public class CmdPWD extends FtpCmd {

		@Override
		public void run() {
			// TODO Auto-generated method stub
			try {
				String pwd = mFTPClient.currentDirectory();
				logv("pwd --- > " + pwd);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}

	public class CmdLIST extends FtpCmd {

		@Override
		public void run() {
			// TODO Auto-generated method stub
			try {
				mCurrentPWD = mFTPClient.currentDirectory();
				FTPFile[] ftpFiles = mFTPClient.list();
				logv(" Request Size  : " + ftpFiles.length);
				synchronized (mLock) {
					mFileList.clear();
					mFileList.addAll(Arrays.asList(ftpFiles));
				}
				mHandler.sendEmptyMessage(MSG_CMD_LIST_OK);

			} catch (Exception ex) {
				mHandler.sendEmptyMessage(MSG_CMD_LIST_FAILED);
				ex.printStackTrace();
			}
		}
	}

	public class CmdCWD extends FtpCmd {

		String realivePath;

		public CmdCWD(String path) {
			realivePath = path;
		}

		@Override
		public void run() {
			try {
				mFTPClient.changeDirectory(realivePath);
				mHandler.sendEmptyMessage(MSG_CMD_CWD_OK);
			} catch (Exception ex) {
				mHandler.sendEmptyMessage(MSG_CMD_CWD_FAILED);
				ex.printStackTrace();
			}
		}
	}

	public class CmdDELE extends FtpCmd {

		String realivePath;
		boolean isDirectory;

		public CmdDELE(String path, boolean isDirectory) {
			realivePath = path;
			this.isDirectory = isDirectory;
		}

		@Override
		public void run() {
			try {
				if (isDirectory) {
					mFTPClient.deleteDirectory(realivePath);
				} else {
					mFTPClient.deleteFile(realivePath);
				}
				mHandler.sendEmptyMessage(MSG_CMD_DELE_OK);
			} catch (Exception ex) {
				mHandler.sendEmptyMessage(MSG_CMD_DELE_FAILED);
				ex.printStackTrace();
			}
		}
	}

	public class CmdRENAME extends FtpCmd {

		String newPath;

		public CmdRENAME(String newPath) {
			this.newPath = newPath;
		}

		@Override
		public void run() {
			try {
				mFTPClient.rename(mFileList.get(mSelectedPosistion).getName(),
						newPath);
				mHandler.sendEmptyMessage(MSG_CMD_RENAME_OK);
			} catch (Exception ex) {
				mHandler.sendEmptyMessage(MSG_CMD_RENAME_FAILED);
				ex.printStackTrace();
			}
		}
	}

//	public class CmdDownLoad extends AsyncTask<Void, Integer, Boolean> {
//
//		public CmdDownLoad() {
//
//		}
//
//		@Override
//		protected Boolean doInBackground(Void... params) {
//			try {
//				String localPath = getParentRootPath() + File.separator
//						+ mFileList.get(mSelectedPosistion).getName();
//				mFTPClient.download(
//						mFileList.get(mSelectedPosistion).getName(),
//						new File(localPath),
//						new DownloadFTPDataTransferListener(mFileList.get(
//								mSelectedPosistion).getSize()));
//			} catch (Exception ex) {
//				ex.printStackTrace();
//				return false;
//			}
//
//			return true;
//		}
//
//		protected void onProgressUpdate(Integer... progress) {
//
//		}
//
//		protected void onPostExecute(Boolean result) {
//			toast(result ? "下载成功" : "下载失败");
//			progressDialog.dismiss();
//		}
//	}

	public class CmdUpload extends AsyncTask<String, Integer, Boolean> {

		String path;

		public CmdUpload() {

		}

		@Override
		protected Boolean doInBackground(String... params) {
			path = params[0];
			try {
				//1.先判断服务器上有没有这个文件,并且文件长度跟现在的文件长度一致
				int idx = path.lastIndexOf("/");
				String strFilename = path.substring(idx +1);
				long len = mFTPClient.fileSize(strFilename);

				File file = new File(path);
				//如果文件长度相等,不用用上传,直接结束

				if(len == file.length())
				{
					return true;
				}

				FileInputStream inputStream = new FileInputStream(file);
				inputStream.close();

				mFTPClient.upload(file, new DownloadFTPDataTransferListener(
						file.length()));
			} catch (Exception ex) {
				ex.printStackTrace();
				return false;
			}

			return true;
		}

		protected void onProgressUpdate(Integer... progress) {

		}

		protected void onPostExecute(Boolean result) {
			toast(result ? path + "上传成功" : "上传失败");

			if(result == true)
			    mHandler.sendEmptyMessage(MSG_CMD_UPLOAD_SUCCESS);
			else
			    mHandler.sendEmptyMessage(MSG_CMD_UPLOAD_FAILED);
		}
	}

	private class DownloadFTPDataTransferListener implements
			FTPDataTransferListener {

		private int totolTransferred = 0;
		private long fileSize = -1;

		public DownloadFTPDataTransferListener(long fileSize) {
			if (fileSize <= 0) {
				throw new RuntimeException(
						"the size of file muset be larger than zero.");
			}
			this.fileSize = fileSize;
		}

		@Override
		public void aborted() {
			// TODO Auto-generated method stub
			logv("FTPDataTransferListener : aborted");
		}

		@Override
		public void completed() {
			// TODO Auto-generated method stub
			logv("FTPDataTransferListener : completed");
			//setLoadProgress(mPbLoad.getMax());
		}

		@Override
		public void failed() {
			// TODO Auto-generated method stub
			logv("FTPDataTransferListener : failed");
		}

		@Override
		public void started() {
			// TODO Auto-generated method stub
			logv("FTPDataTransferListener : started");
		}

		@Override
		public void transferred(int length) {
			totolTransferred += length;
			float percent = (float) totolTransferred / this.fileSize;
			logv("FTPDataTransferListener : transferred # percent @@" + percent);
			//setLoadProgress((int) (percent * mPbLoad.getMax()));
		}
	}

	//=========================================================

	//Justin. 同步一个目录.目录由参数给出.
	public class CmdRsync extends AsyncTask<String, Integer, Boolean> {

		String path;
		String strCurRemotePath = "";	//当前的远程目录

		public CmdRsync() {

		}

		boolean ChangeDir(String dir)
		{
			//如果要切换的目录跟当前远程目录同样,不用再切换
			if(dir == strCurRemotePath)
				return true;

			try {
				//mFTPClient.changeDirectory(dir);
				strCurRemotePath = dir;
			} catch (Exception ex) {
				ex.printStackTrace();
				return false;
			}

			return true;
		}
		protected Boolean UploadFile(File file, String strDest) {


			if(file.isDirectory()) {
				//如果是子目录,需要切换服务器的目录.并且在处理完这个目录后,回退到上一级目录
				String strCurDir = strDest;
				String strDirName = file.getName();
				strDirName = strDirName.substring(strDirName.lastIndexOf("//") + 1);	//移除全路径,只留一组目录名
				String strSubDir = strDest + strDirName;


				File[] filelist = file.listFiles();
				for(int i = 0 ; i < filelist.length; ++i) {
					UploadFile(filelist[i], strSubDir);	//递归调用.
				}

			}
			

			try {

				//切换目录,上传文件
				ChangeDir(strDest);

				String strName = file.getName();

				long len = mFTPClient.fileSize(strName);

				//如果文件长度相等,不用用上传,直接结束
				if(len == file.length())
				{
					return true;
				}

				//上传文件
				mFTPClient.upload(file, new DownloadFTPDataTransferListener(
						file.length()));
			} catch (IOException e) {
				e.printStackTrace();
			} catch (FTPIllegalReplyException e) {
				e.printStackTrace();
			} catch (FTPException e) {
				e.printStackTrace();
			} catch (FTPDataTransferException e) {
				e.printStackTrace();
			} catch (FTPAbortedException e) {
				e.printStackTrace();
			}

			return  true;
		}

		@Override
		protected Boolean doInBackground(String... strings) {

			while(false == mExitSync)
			{
				if(mFTPClient.isConnected())
					break;

				try {
					sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

			//1.切到对应的目录.
			ChangeDir(mDestPath);

			//2.顺序上传文件.
			path = strings[0];

			File f = new File(path);//strPath为路径
			UploadFile(f, mDirectory);

			//上传完成后,发送完成状态.
			//mHandler.sendEmptyMessage(MSG_CMD_SYNC_OK);
			ReportMessage(CommandResult.CMD_SYNC, 1);
			mExitSync = true;
			return true;
		}
	}

	public void StartSync()
	{
		mExitSync = false;
		new CmdRsync().execute(mDirectory);
	}
}
