package teamExtension;

import burp.ICookie;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.util.List;

class ServerListenThread implements Runnable {

    private BufferedReader streamIn;
    private SharedValues sharedValues;
    private boolean exit;

    ServerListenThread(Socket socket, SharedValues sharedValues) {
        this.exit = false;
        try {
            this.streamIn = new BufferedReader(new InputStreamReader(socket.getInputStream
                    ()));
            this.sharedValues = sharedValues;
        }
        catch (IOException iOException) {
            System.out.println("Error getting input stream: " + iOException);
        }
    }

    void stop() {
        exit = true;
    }

    @Override
    public void run() {
        do {
            try {
                if (exit) {
                    break;
                }
                String message = this.streamIn.readLine();
                if (message == null) {
                    System.out.println("Stream is broke");
                    this.sharedValues.doneListening();
                    break;
                }
                String decryptedMessage = this.sharedValues.getAESCrypter().decrypt(message);
                BurpTCMessage msg = this.sharedValues.getGson().fromJson(decryptedMessage, BurpTCMessage.class);
                switch (msg.getMessageType()) {
                    case COOKIE_MESSAGE:
                        List<ICookie> newCookies = this.sharedValues.getGson().fromJson(msg.getData(), SharedValues.cookieJsonListType);
                        for (ICookie newCookie : newCookies) {
                            this.sharedValues.getCallbacks().updateCookieJar(newCookie);
                        }
                        break;
                    case SCAN_ISSUE_MESSAGE:
                        System.out.println("Got new issue from client");
                        ScanIssue decodedIssue = this.sharedValues.getGson().fromJson(msg.getData(), ScanIssue.class);
                        /*
                        This hack is to bypass an infinite loop that occurs when I inject a new issue with addScanIssue()
                        and I also have a ScanListener setup. When I add an issue the Scanlistener activates which sends
                        out a new issue to addScanIssue.....You get the point. To bypass that, since passing a custom
                        ScanIssue to addScanIssue() looks no different than the internal one, I commandeer the remediation
                        value to set it to true. This is normally null in all the issues I've seen but if another
                        extension sets it to something meaningful this will clobber it. Sorry.
                         */
                        decodedIssue.setRemediation();
                        this.sharedValues.getCallbacks().addScanIssue(decodedIssue);
                        break;
                    case SYNC_SCOPE_MESSAGE:
                        try {
                            this.sharedValues.getCallbacks().loadConfigFromJson(msg.getData());
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        break;
                    case BURP_MESSAGE:
                        this.sharedValues.getCallbacks().addToSiteMap(msg.getRequestResponse());
                        break;
                    case REPEATER_MESSAGE:
                        this.sharedValues.getCallbacks().sendToRepeater(
                                msg.getRequestResponse().getHttpService().getHost(),
                                msg.getRequestResponse().getHttpService().getPort(),
                                msg.getRequestResponse().getHttpService().getProtocol()
                                    .equalsIgnoreCase("https"),
                                msg.getRequestResponse().getRequest(),
                            "BurpTC Payload");
                        break;
                    case INTRUDER_MESSAGE:
                        this.sharedValues.getCallbacks().sendToIntruder(
                                msg.getRequestResponse().getHttpService().getHost(),
                                msg.getRequestResponse().getHttpService().getPort(),
                                msg.getRequestResponse().getHttpService().getProtocol()
                                    .equalsIgnoreCase("https"),
                                msg.getRequestResponse().getRequest());
                        break;
                    case NEW_MEMBER_MESSAGE:
                        if (!this.sharedValues.getServerConnection().getCurrentRoom().equals("server")) {
                            this.sharedValues.getServerListModel().removeAllElements();
                            for (String member : msg.getData().split(",")) {
                                this.sharedValues.getServerListModel().addElement(member);
                            }
                        }
                        break;
                    case GET_ROOMS_MESSAGE:
                        this.sharedValues.getServerListModel().removeAllElements();
                        if (msg.getData().length() == 0) {
                            this.sharedValues.getServerListModel().addElement("No rooms currently");
                        } else {
                            for (String member : msg.getData().split(",")) {
                                this.sharedValues.getServerListModel().addElement(member);
                            }
                        }
                        break;
                    default:
                        System.out.println("Bad msg type");
                }
            }
            catch (IOException iOException) {
                System.out.println("Listening error: " + iOException.getMessage());
                this.sharedValues.doneListening();
                break;
            }
        } while (!exit);
    }
}
