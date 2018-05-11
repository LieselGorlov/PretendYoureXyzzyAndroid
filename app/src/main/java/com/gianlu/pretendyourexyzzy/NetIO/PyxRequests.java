package com.gianlu.pretendyourexyzzy.NetIO;

import android.content.SharedPreferences;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.gianlu.commonutils.CommonUtils;
import com.gianlu.commonutils.Logging;
import com.gianlu.commonutils.NameValuePair;
import com.gianlu.commonutils.Preferences.Prefs;
import com.gianlu.pretendyourexyzzy.NetIO.Models.CardSet;
import com.gianlu.pretendyourexyzzy.NetIO.Models.FirstLoad;
import com.gianlu.pretendyourexyzzy.NetIO.Models.Game;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GameCards;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GameInfo;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GamesList;
import com.gianlu.pretendyourexyzzy.NetIO.Models.User;
import com.gianlu.pretendyourexyzzy.PKeys;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import okhttp3.Response;

public final class PyxRequests {

    @NonNull
    public static PyxRequestWithResult<FirstLoad> firstLoad() {
        return new PyxRequestWithResult<>(Pyx.Op.FIRST_LOAD, new Pyx.Processor<FirstLoad>() {
            @NonNull
            @Override
            public FirstLoad process(@NonNull SharedPreferences prefs, @NonNull Response response, @NonNull JSONObject obj) throws JSONException {
                User user = null;
                if (obj.getBoolean("ip") && obj.has("n")) {
                    String lastSessionId = Prefs.getString(prefs, PKeys.LAST_JSESSIONID, null);
                    if (lastSessionId != null) user = new User(lastSessionId, obj);
                }

                return new FirstLoad(obj, user);
            }
        });
    }

    @NonNull
    public static PyxRequest logout() {
        return new PyxRequest(Pyx.Op.LOGOUT);
    }

    @NonNull
    public static PyxRequestWithResult<User> register(@NonNull String nickname) {
        return new PyxRequestWithResult<>(Pyx.Op.REGISTER, new Pyx.Processor<User>() {
            @NonNull
            @Override
            public User process(@NonNull SharedPreferences prefs, @NonNull Response response, @NonNull JSONObject obj) throws JSONException {
                String sessionId = findSessionId(response);
                if (sessionId == null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1)
                        throw new JSONException(new NullPointerException("Cannot find cookie JSESSIONID!"));
                    else
                        throw new JSONException("Cannot find cookie JSESSIONID!");
                }

                return new User(sessionId, obj);
            }
        }, new NameValuePair("n", nickname));
    }

    @Nullable
    private static String findSessionId(Response response) {
        for (String cookie : response.headers("Set-Cookie")) {
            String[] segments = cookie.split(";");
            String[] keyValue = segments[0].split("=");
            if (Objects.equals(keyValue[0], "JSESSIONID"))
                return keyValue[1];
        }

        return null;
    }

    @NonNull
    public static PyxRequest leaveGame(int gid) {
        return new PyxRequest(Pyx.Op.LEAVE_GAME, new NameValuePair("gid", String.valueOf(gid)));
    }

    public static PyxRequest changeGameOptions(int gid, @NonNull Game.Options options) throws JSONException {
        return new PyxRequest(Pyx.Op.CHANGE_GAME_OPTIONS,
                new NameValuePair("gid", String.valueOf(gid)),
                new NameValuePair("go", options.toJson().toString()));
    }

    @NonNull
    public static PyxRequestWithResult<Integer> createGame() {
        return new PyxRequestWithResult<>(Pyx.Op.CREATE_GAME, new Pyx.Processor<Integer>() {
            @NonNull
            @Override
            public Integer process(@NonNull SharedPreferences prefs, @NonNull Response response, @NonNull JSONObject obj) throws JSONException {
                return obj.getInt("gid");
            }
        });
    }

    @NonNull
    public static PyxRequest joinGame(int gid, @Nullable String password) {
        return new PyxRequest(Pyx.Op.JOIN_GAME, new NameValuePair("gid", String.valueOf(gid)), new NameValuePair("pw", password));
    }

    @NonNull
    public static PyxRequest spectateGame(int gid, @Nullable String password) {
        return new PyxRequest(Pyx.Op.SPECTATE_GAME, new NameValuePair("gid", String.valueOf(gid)), new NameValuePair("pw", password));
    }

    @NonNull
    public static PyxRequestWithResult<GameInfo> getGameInfo(int gid) {
        return new PyxRequestWithResult<>(Pyx.Op.GET_GAME_INFO, new Pyx.Processor<GameInfo>() {
            @NonNull
            @Override
            public GameInfo process(@NonNull SharedPreferences prefs, @NonNull Response response, @NonNull JSONObject obj) throws JSONException {
                return new GameInfo(obj);
            }
        }, new NameValuePair("gid", String.valueOf(gid)));
    }

    @NonNull
    public static PyxRequest sendGameMessage(int gid, @NonNull String msg) {
        return new PyxRequest(Pyx.Op.GAME_CHAT, new NameValuePair("gid", String.valueOf(gid)), new NameValuePair("m", msg));
    }

    @NonNull
    public static PyxRequestWithResult<List<String>> getNamesList() {
        return new PyxRequestWithResult<>(Pyx.Op.GET_NAMES_LIST, new Pyx.Processor<List<String>>() {
            @NonNull
            @Override
            public List<String> process(@NonNull SharedPreferences prefs, @NonNull Response response, @NonNull JSONObject obj) throws JSONException {
                return CommonUtils.toStringsList(obj.getJSONArray("nl"), false);
            }
        });
    }

    @NonNull
    public static PyxRequest removeCardcastDeck(int gid, @NonNull String code) {
        return new PyxRequest(Pyx.Op.REMOVE_CARDCAST_CARD_SET,
                new NameValuePair("gid", String.valueOf(gid)),
                new NameValuePair("cci", code));
    }

    @NonNull
    public static PyxRequestWithResult<GameCards> getGameCards(int gid) {
        return new PyxRequestWithResult<>(Pyx.Op.GET_GAME_CARDS, new Pyx.Processor<GameCards>() {
            @NonNull
            @Override
            public GameCards process(@NonNull SharedPreferences prefs, @NonNull Response response, @NonNull JSONObject obj) throws JSONException {
                return new GameCards(obj);
            }
        }, new NameValuePair("gid", String.valueOf(gid)));
    }

    @NonNull
    public static PyxRequest startGame(int gid) {
        return new PyxRequest(Pyx.Op.START_GAME, new NameValuePair("gid", String.valueOf(gid)));
    }

    @NonNull
    public static PyxRequest judgeCard(int gid, int cardId) {
        return new PyxRequest(Pyx.Op.JUDGE_SELECT,
                new NameValuePair("gid", String.valueOf(gid)),
                new NameValuePair("cid", String.valueOf(cardId)));
    }

    @NonNull
    public static PyxRequest playCard(int gid, int cardId, @Nullable String customText) {
        return new PyxRequest(Pyx.Op.PLAY_CARD,
                new NameValuePair("gid", String.valueOf(gid)),
                new NameValuePair("cid", String.valueOf(cardId)),
                new NameValuePair("m", customText));
    }

    @NonNull
    public static PyxRequestWithResult<List<CardSet>> listCardcastDecks(int gid, @NonNull final Cardcast cardcast) {
        return new PyxRequestWithResult<>(Pyx.Op.LIST_CARDCAST_CARD_SETS, new Pyx.Processor<List<CardSet>>() {
            @NonNull
            @Override
            public List<CardSet> process(@NonNull SharedPreferences prefs, @NonNull Response response, @NonNull JSONObject obj) throws JSONException {
                List<CardSet> cards = new ArrayList<>();
                JSONArray array = obj.getJSONArray("css");
                for (int i = 0; i < array.length(); i++) {
                    CardSet set = new CardSet(array.getJSONObject(i));
                    cards.add(set);

                    try {
                        set.cardcastDeck = cardcast.guessDeckSync(set);
                    } catch (IOException | ParseException ex) {
                        Logging.log(ex);
                    }
                }

                return cards;
            }
        }, new NameValuePair("gid", String.valueOf(gid)));
    }

    @NonNull
    public static PyxRequest addCardcastDeck(int gid, String code) {
        return new PyxRequest(Pyx.Op.ADD_CARDCAST_CARD_SET,
                new NameValuePair("gid", String.valueOf(gid)),
                new NameValuePair("cci", code));
    }

    @NonNull
    public static PyxRequestWithResult<GamesList> getGamesList() {
        return new PyxRequestWithResult<>(Pyx.Op.GET_GAMES_LIST, new Pyx.Processor<GamesList>() {
            @NonNull
            @Override
            public GamesList process(@NonNull SharedPreferences prefs, @NonNull Response response, @NonNull JSONObject obj) throws JSONException {
                JSONArray array = obj.getJSONArray("gl");
                final GamesList games = new GamesList(obj.getInt("mg"));
                for (int i = 0; i < array.length(); i++)
                    games.add(new Game(array.getJSONObject(i)));
                return games;
            }
        });
    }
}
