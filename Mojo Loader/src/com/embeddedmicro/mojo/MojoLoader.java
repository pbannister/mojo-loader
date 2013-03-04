package com.embeddedmicro.mojo;

import gnu.io.CommPort;
import gnu.io.CommPortIdentifier;
import gnu.io.SerialPort;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;

import org.eclipse.swt.widgets.Display;

public class MojoLoader {
	private Display display;
	private TextProgressBar bar;
	private InputStream in;
	private OutputStream out;
	private SerialPort serialPort;
	private Callback callback;

	public MojoLoader(Display display, TextProgressBar bar, Callback callback) {
		this.display = display;
		this.bar = bar;
		this.callback = callback;
	}

	public static ArrayList<String> listPorts() {
		ArrayList<String> ports = new ArrayList<String>();
		@SuppressWarnings("unchecked")
		java.util.Enumeration<CommPortIdentifier> portEnum = CommPortIdentifier
				.getPortIdentifiers();
		while (portEnum.hasMoreElements()) {
			CommPortIdentifier portIdentifier = portEnum.nextElement();
			if (portIdentifier.getPortType() == CommPortIdentifier.PORT_SERIAL) {
				ports.add(portIdentifier.getName());
			}
		}
		return ports;
	}

	private void updateProgress(final float value) {
		display.asyncExec(new Runnable() {
			public void run() {
				if (bar.isDisposed())
					return;
				bar.setSelection((int) (value * 100.0f));
			}
		});
	}

	private void updateText(final String text) {
		display.asyncExec(new Runnable() {
			public void run() {
				if (bar.isDisposed())
					return;
				bar.setText(text);
			}
		});
	}

	private int read(int timeout) throws IOException, TimeoutException {
		long initTime = System.currentTimeMillis();
		while (true)
			if (in.available() > 0)
				return in.read();
			else {
				try {
					Thread.sleep(10);
				} catch (InterruptedException e) {

				}
				if (System.currentTimeMillis() - initTime >= timeout)
					throw new TimeoutException(
							"Timeout while reading from serial port!");
			}
	}
	
	private void restartMojo() throws InterruptedException {
		serialPort.setDTR(true);
		Thread.sleep(5);
		serialPort.setDTR(false);
		Thread.sleep(5);
		serialPort.setDTR(true);
		Thread.sleep(700);
	}

	public void clearFlash(final String port) {
		new Thread() {
			public void run() {
				updateText("Connecting...");
				updateProgress(0.0f);
				try {
					connect(port);
				} catch (Exception e) {
					onError(e.getMessage());
					return;
				}

				try {
					restartMojo();
				} catch (InterruptedException e) {
					onError(e.getMessage());
					return;
				}

				try {
					out.write('!'); // Interrupt boot process
					while (in.available() > 0)
						in.skip(in.available()); // Flush the buffer
					if (read(1000) != 'R') {
						onError("Mojo did not respond! Make sure the port is correct.");
						return;
					}

					updateText("Clearing...");

					out.write('C'); // Write to flash

					if (read(1000) != 'D') {
						onError("Mojo did not acknowledge flash erase!");
						return;
					}

					updateText("Done");
					updateProgress(1.0f);

				} catch (IOException | TimeoutException e) {
					onError(e.getMessage());
					return;
				}

				try {
					in.close();
					out.close();
				} catch (IOException e) {
					onError(e.getMessage());
					return;
				}

				serialPort.close();
				callback.onSuccess();
			}
		}.start();
	}

	public void sendBin(final String port, final String binFile,
			final boolean flash, final boolean verify) {
		new Thread() {
			public void run() {
				updateText("Connecting...");
				updateProgress(0.0f);
				try {
					connect(port);
				} catch (Exception e) {
					onError(e.getMessage());
					return;
				}

				File file = new File(binFile);
				InputStream bin = null;
				try {
					bin = new BufferedInputStream(new FileInputStream(file));
				} catch (FileNotFoundException e) {
					onError("The bin file could not be opened!");
					return;
				}

				try {
					restartMojo();
				} catch (InterruptedException e) {
					onError(e.getMessage());
					try {
						bin.close();
					} catch (IOException e1) {
						e1.printStackTrace();
					}
					return;
				}

				try {
					out.write('!'); // Interrupt boot process
					while (in.available() > 0)
						in.skip(in.available()); // Flush the buffer
					if (read(1000) != 'R') {
						onError("Mojo did not respond! Make sure the port is correct.");
						bin.close();
						return;
					}

					updateText("Loading...");

					if (flash) {
						out.write('W'); // Write to flash
					} else {
						out.write('I'); // Write to FPGA
					}

					int length = (int) file.length();
					byte[] buff = new byte[4];

					for (int i = 0; i < 4; i++) {
						buff[i] = (byte) (length >> (i * 8) & 0xff);
					}

					out.write(buff);

					if (read(1000) != 'O') {
						onError("Mojo did not acknowledge transfer size!");
						bin.close();
						return;
					}

					int num;
					int count = 0;
					int oldCount = 0;
					int percent = length / 100;
					byte[] data = new byte[percent];
					while (true) {
						int avail = bin.available();
						avail = avail > percent ? percent : avail;
						if (avail == 0)
							break;
						int read = bin.read(data, 0, avail);
						out.write(data,0,read);
						count+=read;

						if (count - oldCount > percent) {
							oldCount = count;
							float prog = (float) count / length;
							updateProgress(prog);
						}
					}

					if (read(1000) != 'D') {
						onError("Mojo did not acknowledge the transfer!");
						bin.close();
						return;
					}

					bin.close();

					if (flash && verify) {
						updateText("Verifying...");
						bin = new BufferedInputStream(new FileInputStream(file));
						out.write('R');

						int size = (int) (file.length() + 5);
						for (int i = 0; i < 4; i++) {
							buff[i] = (byte) (size >> (i * 8) & 0xff);
						}
						out.write(buff);

						int tmp;
						if ((tmp = read(1000)) != 0xAA) {
							onError("Flash does not contain valid start byte! Got: "
									+ tmp);
							bin.close();
							return;
						}

						int flashSize = 0;
						for (int i = 0; i < 4; i++) {
							flashSize |= read(1000) << (i * 8);
						}

						if (flashSize != size) {
							onError("File size mismatch!\nExpected " + size
									+ " and got " + flashSize);
							bin.close();
							return;
						}

						count = 0;
						oldCount = 0;
						while ((num = bin.read()) != -1) {
							if (read(1000) != num) {
								onError("Verification failed!");
								bin.close();
								return;
							}
							count++;
							if (count - oldCount > percent) {
								oldCount = count;
								float prog = (float) count / length;
								updateProgress(prog);
							}
						}
					}

					bin.close();

					if (flash) {
						out.write('L');
						if (read(2000) != 'O') {
							onError("Could not start FPGA!");
							return;
						}
					}

					out.write('S');
				} catch (IOException | TimeoutException e) {
					onError(e.getMessage());
					return;
				}

				updateText("Done");
				updateProgress(1.0f);

				try {
					in.close();
					out.close();
				} catch (IOException e) {
					onError(e.getMessage());
					return;
				}

				serialPort.close();
				callback.onSuccess();
			}
		}.start();
	}

	private void onError(String e) {
		callback.onError(e);
		updateProgress(0.0f);
		updateText("");
		try {
			if (in != null)
				in.close();
			if (out != null)
				out.close();
		} catch (IOException err) {
			System.err.print(err);
		}
		if (serialPort != null)
			serialPort.close();
	}

	private void connect(String portName) throws Exception {
		if (portName.equals(""))
			throw new Exception("A serial port must be selected!");
		CommPortIdentifier portIdentifier = CommPortIdentifier
				.getPortIdentifier(portName);
		if (portIdentifier.isCurrentlyOwned()) {
			System.out.println("Error: Port is currently in use");
		} else {
			CommPort commPort = portIdentifier.open(this.getClass().getName(),
					2000);

			if (commPort instanceof SerialPort) {
				serialPort = (SerialPort) commPort;
				serialPort.setSerialPortParams(115200, SerialPort.DATABITS_8,
						SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);

				in = serialPort.getInputStream();
				out = serialPort.getOutputStream();

			} else {
				System.out.println("Error: Only serial ports can be used!");
			}
		}
	}

}
