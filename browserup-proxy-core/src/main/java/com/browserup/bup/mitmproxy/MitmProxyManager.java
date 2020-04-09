package com.browserup.bup.mitmproxy;

import com.browserup.bup.mitmproxy.addons.AddonsManagerAddOn;
import com.browserup.bup.mitmproxy.addons.HarCaptureFilterAddOn;
import com.browserup.bup.mitmproxy.management.AddonsManagerClient;
import com.browserup.bup.mitmproxy.management.HarCaptureFilterManager;
import com.browserup.harreader.model.Har;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class MitmProxyManager {
  public static final int ADDONS_MANAGER_API_PORT = 8088;
  private static final Logger LOGGER = LoggerFactory.getLogger(MitmProxyManager.class);

  private StartedProcess startedProcess = null;
  private HarCaptureFilterAddOn harCaptureFilterAddOn = new HarCaptureFilterAddOn();
  private AddonsManagerAddOn addonsManagerAddOn = new AddonsManagerAddOn(ADDONS_MANAGER_API_PORT);
  private AddonsManagerClient addonsManagerClient = new AddonsManagerClient(ADDONS_MANAGER_API_PORT);
  private HarCaptureFilterManager harCaptureFilterManager = new HarCaptureFilterManager(addonsManagerClient);

  private Integer proxyPort = 0;

  private PipedInputStream pipedInputStream;

  private Har lastHar = new Har();
  private boolean isRunning = false;

  private MitmProxyManager() {}

  public static MitmProxyManager getInstance() {
    return new MitmProxyManager();
  }

  public void start(int port) {
    try {
      startProxy(port);
      this.proxyPort = port;
    } catch (Exception ex) {
      LOGGER.error("Failed to start proxy", ex);
      stop();
      throw ex;
    }
  }

  public Integer getProxyPort() {
    return proxyPort;
  }

  public void stop() {
    getHar();
    this.isRunning = false;

    try {
      pipedInputStream.close();
    } catch (IOException e) {
      LOGGER.warn("Couldn't close piped input stream", e);
    }
    startedProcess.getProcess().destroy();

  }

  private void startProxy(int port) {
    List<String> command = new ArrayList<String>() {{
      add("mitmdump");
      add("-p");
      add(String.valueOf(port));
    }};
    command.addAll(Arrays.asList(harCaptureFilterAddOn.getCommandParams()));
    command.addAll(Arrays.asList(addonsManagerAddOn.getCommandParams()));

    LOGGER.info("Starting proxy using command: " + String.join(" ", command));

    this.pipedInputStream = new PipedInputStream();
    ProcessExecutor processExecutor;
    try {
      processExecutor = new ProcessExecutor(command)
          .readOutput(true)
          .redirectOutput(Slf4jStream.ofCaller().asInfo())
          .redirectOutputAlsoTo(new PipedOutputStream(pipedInputStream));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    try {
      startedProcess = processExecutor.start();
    } catch (Exception ex) {
      throw new RuntimeException("Couldn't start mitmproxy process", ex);
    }

    StringBuilder output = new StringBuilder();
    readOutputOfMimtproxy(pipedInputStream, output);

    try {
      Awaitility.await().
          atMost(5, TimeUnit.SECONDS).
          until(() -> output.toString().contains("Proxy server listening"));
    } catch (ConditionTimeoutException ex) {
      LOGGER.error("MitmProxy haven't started properly, output: " + output);
      throw new RuntimeException("Mitmproxy haven't started properly, output: " + output);
    }

    this.isRunning = true;
  }

  private void readOutputOfMimtproxy(PipedInputStream pipedInputStream, StringBuilder output) {
    new Thread(() -> {
      BufferedReader reader = new BufferedReader(new InputStreamReader(pipedInputStream));
      Awaitility.await().atMost(5, TimeUnit.SECONDS).until(reader::ready);

      while (true) {
        try {
          if (!reader.ready())
            break;
          output.append(reader.readLine());
        } catch (IOException e) {
          LOGGER.error("Error while reading output of mitmproxy", e);
        }
      }
    }).start();
  }

  public Har getHar() {
    return getHar(false);
  }

  public Har getHar(boolean cleanHar) {
    if (!this.isRunning) return this.lastHar;

    File harFile = new File(harCaptureFilterManager.getHar(cleanHar));

    Har har;
    try {
      har = new ObjectMapper().readerFor(Har.class).readValue(harFile);
    } catch (IOException e) {
      throw new RuntimeException("Couldn't read HAR file", e);
    }

    this.lastHar = har;
    return har;
  }
}
