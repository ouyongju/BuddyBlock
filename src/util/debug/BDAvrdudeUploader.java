package util.debug;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import util.base.Base;
import util.base.Preferences;

/**
 *
 * @author lbz
 */
public class BDAvrdudeUploader extends BDUploader {
	@Override
	public boolean uploadUsingPreferences(String buildPath, String className, boolean usingProgrammer)
			throws BDRunnerException, Exception {

		Map<String, String> boardPreferences = Base.getBoardPreferences();

		if (usingProgrammer || boardPreferences.get("upload.protocol") == null) {
			String programmer = Preferences.get("programmer");

			BDTarget target = Base.getTarget();

			if (programmer.indexOf(":") != -1) {
				target = Base.targetsTable.get(programmer.substring(0, programmer.indexOf(":")));
				programmer = programmer.substring(programmer.indexOf(":") + 1);
			}

			Collection<String> params = getProgrammerCommands(target, programmer);

			params.add("-Uflash:w:" + buildPath + File.separator + className + ".hex:i");
			return avrdude(params);
		}

		return uploadViaBootloader(buildPath, className);
	}

	private boolean uploadViaBootloader(String buildPath, String className) throws BDRunnerException, Exception {
		Map<String, String> boardPreferences = Base.getBoardPreferences();
		List<String> commandDownloader = new ArrayList<String>();
		String protocol = boardPreferences.get("upload.protocol");

		// avrdude wants "stk500v1" to distinguish it from stk500v2
		if (protocol.equals("stk500")) {
			protocol = "stk500v1";
		}

		String uploadPort = Preferences.get("serial.port");

		// need to do a little dance for Leonardo and derivatives:
		// open then close the port at the magic baudrate (usually 1200 bps)
		// first
		// to signal to the sketch that it should reset into bootloader. after
		// doing
		// this wait a moment for the bootloader to enumerate. On Windows, also
		// must
		// deal with the fact that the COM port number changes from bootloader
		// to
		// sketch.
		String bootloaderPath = boardPreferences.get("bootloader.path");
		if (bootloaderPath != null && (bootloaderPath.equals("caterina") || bootloaderPath.equals("stk500v2")
				|| bootloaderPath.equals("caterina-Arduino_Robot") || bootloaderPath.equals("caterina-LilyPadUSB"))) {
			String caterinaUploadPort = null;

			try {
				// Toggle 1200 bps on selected serial port to force board reset.
				List<String> before = BDSerial.list();

				if (before.contains(uploadPort)) {
					if (verbose) {
						System.out.println("Forcing reset using 1200bps open/close on port " + uploadPort);
					}
					BDSerial.touchPort(uploadPort, 1200);

					// Scanning for available ports seems to open the port or
					// otherwise assert DTR, which would cancel the WDT reset if
					// it happened within 250 ms. So we wait until the reset
					// should
					// have already occured before we start scanning.
					// if (!Base.isMacOS()) Thread.sleep(300);
				}

				// Wait for a port to appear on the list
				int elapsed = 0;

				while (elapsed < 10000) {
					List<String> now = BDSerial.list();
					List<String> diff = new ArrayList<String>(now);

					diff.removeAll(before);
					if (verbose || Preferences.getBoolean("upload.verbose")) {
						System.out.print("PORTS {");
						for (String p : before) {
							System.out.print(p + ", ");
						}
						System.out.print("} / {");
						for (String p : now) {
							System.out.print(p + ", ");
						}
						System.out.print("} => {");
						for (String p : diff) {
							System.out.print(p + ", ");
						}
						System.out.println("}");
					}
					if (diff.size() > 0) {
						caterinaUploadPort = diff.get(0);
						if (verbose || Preferences.getBoolean("upload.verbose")) {
							System.out.println("Found Leonardo upload port: " + caterinaUploadPort);
						}
						break;
					}

					// Keep track of port that disappears
					before = now;
					Thread.sleep(250);
					elapsed += 250;

					// On Windows, it can take a long time for the port to
					// disappear and
					// come back, so use a longer time out before assuming that
					// the selected
					// port is the bootloader (not the sketch).
					if (elapsed >= 5000 && now.contains(uploadPort)) {
						if (verbose || Preferences.getBoolean("upload.verbose")) {
							System.out.println("Uploading using selected port: " + uploadPort);
						}
						caterinaUploadPort = uploadPort;
						break;
					}
				}

				if (caterinaUploadPort == null) {
					// Something happened while detecting port
					throw new BDRunnerException(
							"Could not find a Leonardo on the selected port. Check that you have the correct port selected.  If it is correct, try pressing the board's reset button after initiating the upload.");
				}

				uploadPort = caterinaUploadPort;
			} catch (Exception e) {
				throw new BDRunnerException(e.getMessage());
			}
		}

		commandDownloader.add("-c" + protocol);
		commandDownloader.add("-P" + (Base.isWindows() ? "\\\\.\\" : "") + uploadPort);
		commandDownloader.add("-b" + Integer.parseInt(boardPreferences.get("upload.speed")));
		commandDownloader.add("-D"); // don't erase
		if (!Preferences.getBoolean("upload.verify")) {
			commandDownloader.add("-V");
		} // disable verify
		commandDownloader.add("-Uflash:w:" + buildPath + File.separator + className + ".hex:i");

		if (boardPreferences.get("upload.disable_flushing") == null
				|| boardPreferences.get("upload.disable_flushing").toLowerCase().equals("false")) {
			flushSerialBuffer();
		}
		boolean avrdudeResult = avrdude(commandDownloader);

		// For Leonardo wait until the bootloader serial port disconnects and
		// the sketch serial
		// port reconnects (or timeout after a few seconds if the sketch port
		// never comes back).
		// Doing this saves users from accidentally opening Serial Monitor on
		// the soon-to-be-orphaned
		// bootloader port.

		if (true == avrdudeResult && bootloaderPath != null
				&& (bootloaderPath.equals("caterina") || bootloaderPath.equals("caterina-Arduino_Robot")
						|| bootloaderPath.equals("stk500v2") || bootloaderPath.equals("caterina-LilyPadUSB"))) {
			try {
				Thread.sleep(500);
			} catch (InterruptedException ex) {
			}
			long timeout = System.currentTimeMillis() + 2000;

			while (timeout > System.currentTimeMillis()) {
				List<String> portList = BDSerial.list();

				uploadPort = Preferences.get("serial.port");
				if (portList.contains(uploadPort)) {
					try {
						Thread.sleep(100); // delay to avoid port in use and
											// invalid parameters errors
					} catch (InterruptedException ex) {
					}
					// Remove the magic baud rate (1200bps) to avoid future
					// unwanted board resets
					int serialRate = Preferences.getInteger("serial.debug_rate");

					if (verbose || Preferences.getBoolean("upload.verbose")) {
						System.out.println("Setting baud rate to " + serialRate + " on " + uploadPort);
					}
					BDSerial.touchPort(uploadPort, serialRate);
					break;
				}
				try {
					Thread.sleep(100);
				} catch (InterruptedException ex) {
				}
			}
		}
		return avrdudeResult;
	}

	public boolean avrdude(Collection<String> p1, Collection<String> p2) throws BDRunnerException {
		ArrayList<String> p = new ArrayList<String>(p1);

		p.addAll(p2);
		return avrdude(p);
	}

	public boolean avrdude(Collection<String> params) throws BDRunnerException {
		List<String> commandDownloader = new ArrayList<String>();

		if (Base.isLinux()) {
			if ((new File(Base.getHardwarePath() + "/tools/" + "avrdude")).exists()) {
				commandDownloader.add(Base.getHardwarePath() + "/tools/" + "avrdude");
				commandDownloader.add("-C" + Base.getHardwarePath() + "/tools/avrdude.conf");
			} else {
				commandDownloader.add("avrdude");
			}
		} else {
			commandDownloader.add(Base.getHardwarePath() + "/tools/avr/bin/" + "avrdude");
			commandDownloader.add("-C" + Base.getHardwarePath() + "/tools/avr/etc/avrdude.conf");
		}

		if (verbose || Preferences.getBoolean("upload.verbose")) {
			commandDownloader.add("-v");
			commandDownloader.add("-v");
			commandDownloader.add("-v");
			commandDownloader.add("-v");
		} else {
			commandDownloader.add("-q");
			commandDownloader.add("-q");
		}
		commandDownloader.add("-p" + Base.getBoardPreferences().get("build.mcu"));
		commandDownloader.addAll(params);

		return executeUploadCommand(commandDownloader);
	}

	private Collection<String> getProgrammerCommands(BDTarget target, String programmer) {
		Map<String, String> programmerPreferences = target.getProgrammers().get(programmer);
		List<String> params = new ArrayList<String>();

		params.add("-c" + programmerPreferences.get("protocol"));

		if ("usb".equals(programmerPreferences.get("communication"))) {
			params.add("-Pusb");
		} else if ("serial".equals(programmerPreferences.get("communication"))) {
			params.add("-P" + (Base.isWindows() ? "\\\\.\\" : "") + Preferences.get("serial.port"));
			if (programmerPreferences.get("speed") != null) {
				params.add("-b" + Integer.parseInt(programmerPreferences.get("speed")));
			}
		}
		// XXX: add support for specifying the port address for parallel
		// programmers, although avrdude has a default that works in most cases.

		if (programmerPreferences.get("force") != null
				&& programmerPreferences.get("force").toLowerCase().equals("true")) {
			params.add("-F");
		}

		if (programmerPreferences.get("delay") != null) {
			params.add("-i" + programmerPreferences.get("delay"));
		}

		return params;
	}

	protected boolean burnBootloader(Collection<String> params) throws BDRunnerException {
		Map<String, String> boardPreferences = Base.getBoardPreferences();
		List<String> fuses = new ArrayList<String>();

		fuses.add("-e"); // erase the chip
		if (boardPreferences.get("bootloader.unlock_bits") != null) {
			fuses.add("-Ulock:w:" + boardPreferences.get("bootloader.unlock_bits") + ":m");
		}
		if (boardPreferences.get("bootloader.extended_fuses") != null) {
			fuses.add("-Uefuse:w:" + boardPreferences.get("bootloader.extended_fuses") + ":m");
		}
		fuses.add("-Uhfuse:w:" + boardPreferences.get("bootloader.high_fuses") + ":m");
		fuses.add("-Ulfuse:w:" + boardPreferences.get("bootloader.low_fuses") + ":m");

		if (!avrdude(params, fuses)) {
			return false;
		}

		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
		}

		BDTarget t;
		List<String> bootloader = new ArrayList<String>();
		String bootloaderPath = boardPreferences.get("bootloader.path");

		if (bootloaderPath != null) {
			if (bootloaderPath.indexOf(':') == -1) {
				t = Base.getTarget(); // the current target (associated with the
										// board)
			} else {
				String targetName = bootloaderPath.substring(0, bootloaderPath.indexOf(':'));

				t = Base.targetsTable.get(targetName);
				bootloaderPath = bootloaderPath.substring(bootloaderPath.indexOf(':') + 1);
			}

			File bootloadersFile = new File(t.getFolder(), "bootloaders");
			File bootloaderFile = new File(bootloadersFile, bootloaderPath);

			bootloaderPath = bootloaderFile.getAbsolutePath();

			bootloader.add(
					"-Uflash:w:" + bootloaderPath + File.separator + boardPreferences.get("bootloader.file") + ":i");
		}
		if (boardPreferences.get("bootloader.lock_bits") != null) {
			bootloader.add("-Ulock:w:" + boardPreferences.get("bootloader.lock_bits") + ":m");
		}

		if (bootloader.size() > 0) {
			return avrdude(params, bootloader);
		}

		return true;
	}
}
