package pl.stellarlauncher.discord;

import com.sun.jna.Structure;

import pl.stellarlauncher.DiscordUser;

import com.sun.jna.Callback;
import java.util.Arrays;
import java.util.List;

public class DiscordEventHandlers extends Structure {
    public ReadyCallback ready;
    public DisconnectedCallback disconnected;
    public ErroredCallback errored;
    public JoinGameCallback joinGame;
    public SpectateGameCallback spectateGame;
    public JoinRequestCallback joinRequest;

    @Override
    protected List<String> getFieldOrder() {
        return Arrays.asList("ready", "disconnected", "errored", "joinGame", "spectateGame", "joinRequest");
    }

    public interface ReadyCallback extends Callback {
        void accept(DiscordUser user);
    }

    public interface DisconnectedCallback extends Callback {
        void accept(int errorCode, String message);
    }

    public interface ErroredCallback extends Callback {
        void accept(int errorCode, String message);
    }

    public interface JoinGameCallback extends Callback {
        void accept(String joinSecret);
    }

    public interface SpectateGameCallback extends Callback {
        void accept(String spectateSecret);
    }

    public interface JoinRequestCallback extends Callback {
        void accept(DiscordUser user);
    }
}