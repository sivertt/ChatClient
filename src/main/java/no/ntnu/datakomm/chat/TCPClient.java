package no.ntnu.datakomm.chat;

import javax.xml.soap.Text;
import java.io.*;
import java.net.*;
import java.util.LinkedList;
import java.util.List;

public class TCPClient {
    private PrintWriter toServer;
    private BufferedReader fromServer;
    private Socket connection;

    private OutputStream out;
    private InputStream in;

    // Hint: if you want to store a message for the last error, store it here
    private String lastError = null;

    private final List<ChatListener> listeners = new LinkedList<>();

    /**
     * Connect to a chat server.
     *
     * @param host host name or IP address of the chat server
     * @param port TCP port of the chat server
     * @return True on success, false otherwise
     */
    public boolean connect(String host, int port) {
        try {
            connection = new Socket(host, port);
            out = connection.getOutputStream();
            in = connection.getInputStream();
            toServer = new PrintWriter(out, true);
            fromServer = new BufferedReader(new InputStreamReader(in));
            return true;

        } catch (UnknownHostException e) {
            lastError = "Unknown host";
            System.err.println(lastError);
        } catch (ConnectException e) {
            lastError = "No chat server listening on given port";
            System.err.println(lastError);
        } catch (IOException e) {
            lastError = "I/O error for the socket";
            System.err.println(lastError);
        }
        return false;
    }

    /**
     * Close the socket. This method must be synchronized, because several
     * threads may try to call it. For example: When "Disconnect" button is
     * pressed in the GUI thread, the connection will get closed. Meanwhile, the
     * background thread trying to read server's response will get error in the
     * input stream and may try to call this method when the socket is already
     * in the process of being closed. with "synchronized" keyword we make sure
     * that no two threads call this method in parallel. :)
     */
    public synchronized void disconnect() {
        if(isConnectionActive()) {
            try {
                connection.close();
                connection = null;
                toServer = null;
                fromServer = null;
                onDisconnect();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else {
            System.out.println("No connection to close");
        }
    }

    /**
     * @return true if the connection is active (opened), false if not.
     */
    public boolean isConnectionActive() {
        return connection != null;
    }

    /**
     * Send a command to server.
     *
     * @param cmd A command. It should include the command word and optional attributes, according to the protocol.
     * @return true on success, false otherwise
     */
    private boolean sendCommand(String cmd) {
        if(isConnectionActive()) {
            toServer.println(cmd);
            return true;
        }
        return false;
    }

    /**
     * Send a public message to all the recipients.
     *
     * @param message Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPublicMessage(String message) {
        try {
            String msgToSend = "msg " + message;
            sendCommand(msgToSend);
            return true;
        } catch(Exception e) {
            lastError = "Unable to send message";
            e.printStackTrace();
        }
        return false;
    }

    /**
     * Send a login request to the chat server.
     *
     * @param username Username to use
     */
    public void tryLogin(String username) {
        try {
            String login = "login " +  username;
            sendCommand(login);
        } catch(Exception e) {
            lastError = "Unable to log in";
            e.printStackTrace();
        }
    }

    /**
     * Send a request for latest user list to the server. To get the new users,
     * clear your current user list and use events in the listener.
     */
    public void refreshUserList() {
        try {
            sendCommand("users");
        } catch (Exception e) {
            lastError = "Unable to retrieve user list";
            e.printStackTrace();
        }
    }

    /**
     * Send a private message to a single recipient.
     *
     * @param recipient username of the chat user who should receive the message
     * @param message   Message to send
     * @return true if message sent, false on error
     */
    public boolean sendPrivateMessage(String recipient, String message) {
        try {
            String privmsgToSend = "privmsg " + recipient  + " " + message;
            sendCommand(privmsgToSend);
            return true;
        } catch(Exception e) {
            lastError = "Unable to send message";
            e.printStackTrace();
        }
        return false;
    }


    /**
     * Send a request for the list of commands that server supports.
     */
    public void askSupportedCommands() {
        try {
            String helpMsg = "help";
            sendCommand(helpMsg);
        } catch(Exception e) {
            lastError = "Unable to send message";
            e.printStackTrace();
        }
    }


    /**
     * Wait for chat server's response
     *
     * @return one line of text (one command) received from the server
     */
    private String waitServerResponse() {
        String response;
        try {
            if (isConnectionActive()) {
                response = fromServer.readLine();
                if (response != null) {
                    System.out.println("SERVER: " + response);
                    return response;
                } else {
                    System.out.println("Server returned an empty response");
                }
            }
        } catch (IOException | NullPointerException e) {
            e.printStackTrace();
            disconnect();
        }
        return null;
    }

    /**
     * Get the last error message
     *
     * @return Error message or "" if there has been no error
     */
    public String getLastError() {
        if (lastError != null) {
            return lastError;
        } else {
            return "";
        }
    }

    /**
     * Start listening for incoming commands from the server in a new CPU thread.
     */
    public void startListenThread() {
        // Call parseIncomingCommands() in the new thread.
        Thread t = new Thread(() -> {
            parseIncomingCommands();
        });
        t.start();
    }

    /**
     * Read incoming messages one by one, generate events for the listeners. A loop that runs until
     * the connection is closed.
     */
    private void parseIncomingCommands() {
        while (isConnectionActive()) {
            String response = waitServerResponse();
            // Checks if the response is not null
            if (response != null) {
                String cmd;
                String[] responseParts = null;
                // Checks if the command has a space or not and splits it into an Array.
                if(response.contains(" ")) {
                    responseParts = response.split(" ", 2);
                    cmd = responseParts[0];
                } else {
                    cmd = response;
                }

                switch(cmd) {
                    case "loginok":
                        onLoginResult(true,"");
                        break;
                    case "loginerr":
                        lastError = "A login error occurred!";
                        onLoginResult(false, lastError);
                        break;
                    case "users":
                        String[] users = responseParts[1].split(" ");
                        onUsersList(users);
                        break;
                    case "msg":
                        String[] msg = responseParts[1].split(" ", 2);
                        onMsgReceived(false, msg[0], msg[1]);
                        break;
                    case "privmsg":
                        String[] privmsg = responseParts[1].split(" ", 2);
                        onMsgReceived(true, privmsg[0], privmsg[1]);
                        break;
                    case "msgerr":
                        lastError = responseParts[1];
                        onMsgError(lastError);
                        break;
                    case "cmderr":
                        lastError = responseParts[1];
                        onCmdError(lastError);
                        break;
                    case "msgok":
                        System.out.println("Message sent");
                        break;
                    case "supported":
                        String[] commands = responseParts[1].split(" ");
                        onSupported(commands);
                        break;
                    default:
                        System.out.println("Unable to interpret server response.");
                }
            }
        }
    }

    /**
     * Register a new listener for events (login result, incoming message, etc)
     *
     * @param listener
     */
    public void addListener(ChatListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Unregister an event listener
     *
     * @param listener
     */
    public void removeListener(ChatListener listener) {
        listeners.remove(listener);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////
    // The following methods are all event-notificators - notify all the listeners about a specific event.
    // By "event" here we mean "information received from the chat server".
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Notify listeners that login operation is complete (either with success or
     * failure)
     *
     * @param success When true, login successful. When false, it failed
     * @param errMsg  Error message if any
     */
    private void onLoginResult(boolean success, String errMsg) {
        for (ChatListener l : listeners) {
            l.onLoginResult(success, errMsg);
        }
    }

    /**
     * Notify listeners that socket was closed by the remote end (server or
     * Internet error)
     */
    private void onDisconnect() {
        for (ChatListener l : listeners) {
            l.onDisconnect();
        }
    }

    /**
     * Notify listeners that server sent us a list of currently connected users
     *
     * @param users List with usernames
     */
    private void onUsersList(String[] users) {
        for (ChatListener l : listeners) {
            l.onUserList(users);
        }
    }

    /**
     * Notify listeners that a message is received from the server
     *
     * @param priv   When true, this is a private message
     * @param sender Username of the sender
     * @param text   Message text
     */
    private void onMsgReceived(boolean priv, String sender, String text) {
        TextMessage msg = new TextMessage(sender, priv, text);
        for (ChatListener l : listeners) {
            l.onMessageReceived(msg);
        }
    }

    /**
     * Notify listeners that our message was not delivered
     *
     * @param errMsg Error description returned by the server
     */
    private void onMsgError(String errMsg) {
        for (ChatListener l : listeners) {
            l.onMessageError(errMsg);
        }
    }

    /**
     * Notify listeners that command was not understood by the server.
     *
     * @param errMsg Error message
     */
    private void onCmdError(String errMsg) {
        for (ChatListener l : listeners) {
            l.onCommandError(errMsg);
        }
    }

    /**
     * Notify listeners that a help response (supported commands) was received
     * from the server
     *
     * @param commands Commands supported by the server
     */
    private void onSupported(String[] commands) {
        for (ChatListener l : listeners) {
            l.onSupportedCommands(commands);
        }
    }
}
