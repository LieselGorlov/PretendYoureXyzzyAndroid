package com.gianlu.pretendyourexyzzy.NetIO;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.crashlytics.android.Crashlytics;
import com.gianlu.commonutils.Adapters.GeneralItemsAdapter;
import com.gianlu.commonutils.CommonUtils;
import com.gianlu.commonutils.Logging;
import com.gianlu.commonutils.Preferences.Prefs;
import com.gianlu.pretendyourexyzzy.BuildConfig;
import com.gianlu.pretendyourexyzzy.NetIO.Models.CardSet;
import com.gianlu.pretendyourexyzzy.NetIO.Models.FirstLoad;
import com.gianlu.pretendyourexyzzy.NetIO.Models.Game;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GameCards;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GameInfo;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GamesList;
import com.gianlu.pretendyourexyzzy.NetIO.Models.PollMessage;
import com.gianlu.pretendyourexyzzy.NetIO.Models.User;
import com.gianlu.pretendyourexyzzy.PKeys;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cz.msebera.android.httpclient.HttpEntity;
import cz.msebera.android.httpclient.HttpResponse;
import cz.msebera.android.httpclient.HttpStatus;
import cz.msebera.android.httpclient.NameValuePair;
import cz.msebera.android.httpclient.StatusLine;
import cz.msebera.android.httpclient.client.HttpClient;
import cz.msebera.android.httpclient.client.config.RequestConfig;
import cz.msebera.android.httpclient.client.entity.UrlEncodedFormEntity;
import cz.msebera.android.httpclient.client.methods.HttpPost;
import cz.msebera.android.httpclient.client.protocol.HttpClientContext;
import cz.msebera.android.httpclient.conn.ConnectionPoolTimeoutException;
import cz.msebera.android.httpclient.cookie.Cookie;
import cz.msebera.android.httpclient.impl.client.BasicCookieStore;
import cz.msebera.android.httpclient.impl.client.HttpClients;
import cz.msebera.android.httpclient.impl.cookie.BasicClientCookie2;
import cz.msebera.android.httpclient.message.BasicNameValuePair;
import cz.msebera.android.httpclient.util.EntityUtils;


public class PYX {
    private final static int AJAX_TIMEOUT = (int) TimeUnit.SECONDS.toMillis(5);
    private final static int POLLING_TIMEOUT = (int) TimeUnit.SECONDS.toMillis(30);
    private static PYX instance;
    public final Server server;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler;
    private final HttpClient client;
    private final SharedPreferences preferences;
    private final BasicCookieStore cookieStore;
    private final HttpClientContext ajaxContext;
    private final HttpClientContext pollingContext;
    public FirstLoad firstLoad;
    private PollingThread pollingThread;
    private boolean hasRetriedFirstLoad = false;

    private PYX(Context context) {
        this.handler = new Handler(context.getMainLooper());
        this.cookieStore = new BasicCookieStore();
        this.server = Server.lastServer(context);
        this.preferences = PreferenceManager.getDefaultSharedPreferences(context);
        this.client = HttpClients.custom().setDefaultCookieStore(cookieStore).build();

        this.ajaxContext = new HttpClientContext();
        this.ajaxContext.setRequestConfig(RequestConfig.custom()
                .setConnectTimeout(AJAX_TIMEOUT)
                .setSocketTimeout(AJAX_TIMEOUT)
                .setConnectionRequestTimeout(AJAX_TIMEOUT).build());

        this.pollingContext = new HttpClientContext();
        this.pollingContext.setRequestConfig(RequestConfig.custom()
                .setConnectTimeout(POLLING_TIMEOUT)
                .setSocketTimeout(POLLING_TIMEOUT)
                .setConnectionRequestTimeout(POLLING_TIMEOUT).build());

        String lastJSessionId = getLastJSessionId();
        if (lastJSessionId != null) {
            BasicClientCookie2 cookie = new BasicClientCookie2("JSESSIONID", lastJSessionId);
            cookie.setDomain(server.uri.getHost());
            cookie.setPath(server.uri.getPath());
            cookieStore.addCookie(cookie);

            if (BuildConfig.DEBUG) System.out.println("Trying to resume session: " + cookie);
        }
    }

    public static void invalidate() {
        instance = null;
    }

    public static PYX get(Context context) {
        if (instance == null) instance = new PYX(context);
        return instance;
    }

    private static void raiseException(JSONObject obj) throws PYXException {
        if (obj.optBoolean("e", false) || obj.has("ec")) throw new PYXException(obj);
    }

    @NonNull
    public PollingThread getPollingThread() {
        if (pollingThread == null) startPolling();
        return pollingThread;
    }

    private JSONObject ajaxServletRequestSync(OP operation, NameValuePair... params) throws JSONException, PYXException, IOException {
        return ajaxServletRequestSync(operation, false, params);
    }

    private JSONObject ajaxServletRequestSync(OP operation, boolean retry, NameValuePair... params) throws IOException, JSONException, PYXException {
        HttpPost post = new HttpPost(server.uri.toString() + "AjaxServlet");
        List<NameValuePair> paramsList = new ArrayList<>(Arrays.asList(params));
        paramsList.add(new BasicNameValuePair("o", operation.val));
        post.setEntity(new UrlEncodedFormEntity(paramsList, Charset.forName("UTF-8")));

        HttpResponse resp;
        try {
            resp = client.execute(post, ajaxContext);
        } catch (ConnectionPoolTimeoutException ex) {
            Logging.logMe(ex);
            if (retry) throw ex;
            else return ajaxServletRequestSync(operation, true, params);
        }

        updateJSessionId();

        HttpEntity entity = resp.getEntity();
        if (entity != null) {
            JSONObject obj = new JSONObject(EntityUtils.toString(entity, Charset.forName("UTF-8")));
            post.releaseConnection();

            try {
                raiseException(obj);
            } catch (PYXException ex) {
                Crashlytics.log(operation + "; " + Arrays.toString(params) + "; " + ex.errorCode + "; " + hasRetriedFirstLoad);
                Crashlytics.logException(ex);

                if (operation == OP.FIRST_LOAD && !hasRetriedFirstLoad && Objects.equals(ex.errorCode, "se")) {
                    hasRetriedFirstLoad = true;
                    return ajaxServletRequestSync(operation, false, params);
                }

                throw ex;
            }

            Crashlytics.log(operation + "; " + Arrays.toString(params));

            return obj;
        } else {
            throw new StatusCodeException(resp.getStatusLine());
        }
    }

    public void startPolling() {
        pollingThread = new PollingThread();
        pollingThread.start();
    }

    private void updateJSessionId() {
        for (Cookie cookie : cookieStore.getCookies()) {
            if (Objects.equals(cookie.getName(), "JSESSIONID")) {
                preferences.edit().putString(PKeys.LAST_JSESSIONID.getKey(), cookie.getValue()).apply();
                break;
            }
        }
    }

    private void removeLastJSessionId() {
        preferences.edit().remove(PKeys.LAST_JSESSIONID.getKey()).apply();
    }

    @Nullable
    private String getLastJSessionId() {
        return preferences.getString(PKeys.LAST_JSESSIONID.getKey(), null);
    }

    public void firstLoad(final IResult<FirstLoad> listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject obj = ajaxServletRequestSync(OP.FIRST_LOAD);
                    final FirstLoad result = new FirstLoad(obj);
                    firstLoad = result;

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(PYX.this, result);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    private User registerUserSync(@NonNull final String nickname) throws JSONException, PYXException, IOException {
        JSONObject obj = ajaxServletRequestSync(OP.REGISTER, new BasicNameValuePair("n", nickname));
        final String confirmNick = obj.getString("n");
        if (!Objects.equals(confirmNick, nickname.trim())) throw new RuntimeException("WTF?!");
        return new User(confirmNick);
    }

    public void registerUser(final String nickname, final IResult<User> listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final User user = registerUserSync(nickname);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(PYX.this, user);
                        }
                    });

                    // firestore.setNickname(server, nickname);
                } catch (PYXException | IOException | JSONException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void logout() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (pollingThread != null) {
                        pollingThread.safeStop();
                        pollingThread = null;
                    }

                    ajaxServletRequestSync(OP.LOGOUT);
                    // firestore.loggedOut();

                    removeLastJSessionId();
                    firstLoad = null;
                } catch (IOException | JSONException | PYXException ignored) {
                }
            }
        });
    }

    public void getGamesList(final IResult<GamesList> listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject obj = ajaxServletRequestSync(OP.GET_GAMES_LIST);

                    JSONArray gamesArray = obj.getJSONArray("gl");
                    final GamesList games = new GamesList(obj.getInt("mg"));
                    for (int i = 0; i < gamesArray.length(); i++)
                        games.add(new Game(gamesArray.getJSONObject(i)));

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(PYX.this, games);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void sendGameMessage(final int gid, final String message, final ISuccess listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ajaxServletRequestSync(OP.GAME_CHAT,
                            new BasicNameValuePair("gid", String.valueOf(gid)),
                            new BasicNameValuePair("m", message),
                            new BasicNameValuePair("me", "false"));

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(instance);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void sendMessage(final String message, final ISuccess listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ajaxServletRequestSync(OP.CHAT,
                            new BasicNameValuePair("m", message),
                            new BasicNameValuePair("me", "false"));

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(instance);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void joinGame(final int gid, @Nullable final String password, final ISuccess listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ajaxServletRequestSync(OP.JOIN_GAME,
                            new BasicNameValuePair("gid", String.valueOf(gid)),
                            new BasicNameValuePair("pw", password));

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(instance);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void spectateGame(final int gid, @Nullable final String password, final ISuccess listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ajaxServletRequestSync(OP.SPECTATE_GAME,
                            new BasicNameValuePair("gid", String.valueOf(gid)),
                            new BasicNameValuePair("pw", password));

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(instance);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void leaveGame(final int gid, final ISuccess listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ajaxServletRequestSync(OP.LEAVE_GAME, new BasicNameValuePair("gid", String.valueOf(gid)));

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(instance);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (ex instanceof PYXException && Objects.equals(((PYXException) ex).errorCode, "nitg"))
                                listener.onDone(instance);
                            else listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void getGameInfoAndCards(final int gid, final IGameInfoAndCards listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject infoObj = ajaxServletRequestSync(OP.GET_GAME_INFO, new BasicNameValuePair("gid", String.valueOf(gid)));
                    final GameInfo info = new GameInfo(infoObj);

                    JSONObject cardsObj = ajaxServletRequestSync(OP.GET_GAME_CARDS, new BasicNameValuePair("gid", String.valueOf(gid)));
                    final GameCards cards = new GameCards(cardsObj);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onGameInfoAndCards(info, cards);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void getGameInfo(final int gid, final IResult<GameInfo> listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject obj = ajaxServletRequestSync(OP.GET_GAME_INFO, new BasicNameValuePair("gid", String.valueOf(gid)));
                    final GameInfo info = new GameInfo(obj);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(PYX.this, info);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void getGameCards(final int gid, final IResult<GameCards> listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject obj = ajaxServletRequestSync(OP.GET_GAME_CARDS, new BasicNameValuePair("gid", String.valueOf(gid)));
                    final GameCards cards = new GameCards(obj);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(PYX.this, cards);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void changeGameOptions(final int gid, final Game.Options options, final ISuccess listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ajaxServletRequestSync(OP.CHANGE_GAME_OPTIONS,
                            new BasicNameValuePair("gid", String.valueOf(gid)),
                            new BasicNameValuePair("go", options.toJSON().toString()));

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(PYX.this);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void getNamesList(final IResult<List<String>> listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject obj = ajaxServletRequestSync(OP.GET_NAMES_LIST);
                    final List<String> names = CommonUtils.toStringsList(obj.getJSONArray("nl"), true);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(PYX.this, names);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void playCard(final int gid, final int cid, @Nullable final String customText, final ISuccess listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ajaxServletRequestSync(OP.PLAY_CARD,
                            new BasicNameValuePair("gid", String.valueOf(gid)),
                            new BasicNameValuePair("cid", String.valueOf(cid)),
                            new BasicNameValuePair("m", customText));

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(instance);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void judgeCard(final int gid, final int cid, final ISuccess listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ajaxServletRequestSync(OP.JUDGE_SELECT,
                            new BasicNameValuePair("gid", String.valueOf(gid)),
                            new BasicNameValuePair("cid", String.valueOf(cid)));

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(instance);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void createGame(final IResult<Integer> listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject obj = ajaxServletRequestSync(OP.CREATE_GAME);
                    final int gid = obj.getInt("gid");

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(PYX.this, gid);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void startGame(final int gid, final ISuccess listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ajaxServletRequestSync(OP.START_GAME, new BasicNameValuePair("gid", String.valueOf(gid)));

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(instance);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void listCardcastCardSets(final int gid, @Nullable final Cardcast cardcast, final IResult<List<CardSet>> listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject obj = ajaxServletRequestSync(OP.LIST_CARDCAST_CARD_SETS, new BasicNameValuePair("gid", String.valueOf(gid)));
                    final List<CardSet> sets = CommonUtils.toTList(obj.getJSONArray("css"), CardSet.class);

                    if (cardcast != null)
                        for (CardSet set : sets)
                            set.cardcastDeck = cardcast.guessDeckSync(set);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(PYX.this, sets);
                        }
                    });
                } catch (IOException | JSONException | PYXException | URISyntaxException | ParseException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void addCardcastCardSetSync(int gid, String code) throws JSONException, PYXException, IOException {
        ajaxServletRequestSync(OP.ADD_CARDCAST_CARD_SET,
                new BasicNameValuePair("gid", String.valueOf(gid)),
                new BasicNameValuePair("cci", code));
    }

    public void addCardcastCardSet(final int gid, final String code, final ISuccess listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    addCardcastCardSetSync(gid, code);

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(instance);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public void removeCardcastCardSet(final int gid, final String code, final ISuccess listener) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ajaxServletRequestSync(OP.REMOVE_CARDCAST_CARD_SET,
                            new BasicNameValuePair("gid", String.valueOf(gid)),
                            new BasicNameValuePair("cci", code));

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onDone(instance);
                        }
                    });
                } catch (IOException | JSONException | PYXException ex) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onException(ex);
                        }
                    });
                }
            }
        });
    }

    public enum OP {
        REGISTER("r"),
        FIRST_LOAD("fl"),
        LOGOUT("lo"),
        GET_GAMES_LIST("ggl"),
        CHAT("c"),
        GET_NAMES_LIST("gn"),
        JOIN_GAME("jg"),
        SPECTATE_GAME("vg"),
        LEAVE_GAME("lg"),
        GET_GAME_INFO("ggi"),
        GET_GAME_CARDS("gc"),
        GAME_CHAT("GC"),
        PLAY_CARD("pc"),
        JUDGE_SELECT("js"),
        CREATE_GAME("cg"),
        START_GAME("sg"),
        CHANGE_GAME_OPTIONS("cgo"),
        LIST_CARDCAST_CARD_SETS("clc"),
        ADD_CARDCAST_CARD_SET("cac"),
        REMOVE_CARDCAST_CARD_SET("crc");

        private final String val;

        OP(String val) {
            this.val = val;
        }
    }

    public interface IGameInfoAndCards {
        void onGameInfoAndCards(GameInfo info, GameCards cards);

        void onException(Exception ex);
    }

    public interface ISuccess {
        void onDone(PYX pyx);

        void onException(Exception ex);
    }

    public interface IResult<E> {
        void onDone(PYX pyx, E result);

        void onException(Exception ex);
    }

    public interface IEventListener {
        void onPollMessage(PollMessage message) throws JSONException;

        void onStoppedPolling();
    }

    public static class Server implements GeneralItemsAdapter.Item {
        public final static Map<String, Server> pyxServers = new HashMap<>();
        private static final Pattern URL_PATTERN = Pattern.compile("pyx-(\\d)\\.pretendyoure\\.xyz");

        static {
            pyxServers.put("PYX1", new Server(URI.create("https://pyx-1.pretendyoure.xyz/zy/"), "The Biggest, Blackest Dick"));
            pyxServers.put("PYX2", new Server(URI.create("https://pyx-2.pretendyoure.xyz/zy/"), "A Falcon with a Box on its Head"));
            pyxServers.put("PYX3", new Server(URI.create("https://pyx-3.pretendyoure.xyz/zy/"), "Dickfingers"));
        }

        public final URI uri;
        public final String name;

        public Server(URI uri, String name) {
            this.uri = uri;
            this.name = name;
        }

        Server(JSONObject obj) throws JSONException {
            uri = URI.create(obj.getString("uri"));
            name = obj.getString("name");
        }

        @Nullable
        public static Server fromPyxUrl(String url) {
            Matcher matcher = URL_PATTERN.matcher(url);
            if (matcher.find()) {
                switch (matcher.group(1)) {
                    case "1":
                        return pyxServers.get("PYX1");
                    case "2":
                        return pyxServers.get("PYX2");
                    case "3":
                        return pyxServers.get("PYX3");
                }
            }

            return null;
        }

        public static int indexOf(List<Server> servers, String name) {
            for (int i = 0; i < servers.size(); i++)
                if (Objects.equals(servers.get(i).name, name))
                    return i;

            return -1;
        }

        @NonNull
        public static List<Server> loadUserServers(Context context) {
            List<Server> servers = new ArrayList<>();
            JSONArray array;
            try {
                array = Prefs.getJSONArray(context, PKeys.USER_SERVERS, new JSONArray());
            } catch (JSONException ex) {
                Logging.logMe(ex);
                return new ArrayList<>();
            }

            for (int i = 0; i < array.length(); i++) {
                try {
                    servers.add(new Server(array.getJSONObject(i)));
                } catch (JSONException ex) {
                    Logging.logMe(ex);
                }
            }

            return servers;
        }

        public static boolean isNameOk(Context context, String name) {
            try {
                return !(name == null || name.isEmpty() || getUserServer(context, name) != null);
            } catch (Exception ex) {
                Logging.logMe(ex);
                return false;
            }
        }

        @Nullable
        public static Server getUserServer(Context context, String name) throws JSONException {
            JSONArray array = Prefs.getJSONArray(context, PKeys.USER_SERVERS, new JSONArray());
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                if (Objects.equals(obj.optString("name"), name))
                    return new Server(obj);
            }

            return null;
        }

        @NonNull
        public static Server lastServer(Context context) {
            String name = Prefs.getString(context, PKeys.LAST_SERVER, "PYX1");

            Server server = null;
            if (name.startsWith("PYX")) server = fromPyxUrl(name);

            if (server == null) {
                try {
                    server = getUserServer(context, name);
                } catch (JSONException ex) {
                    Logging.logMe(ex);
                }
            }

            if (server == null) server = pyxServers.get("PYX1");

            return server;
        }

        public static void addServer(Context context, Server server) throws JSONException {
            JSONArray array = Prefs.getJSONArray(context, PKeys.USER_SERVERS, new JSONArray());
            array.put(server.toJSON());
            Prefs.putJSONArray(context, PKeys.USER_SERVERS, array);
        }

        public static void clearUserServers(Context context) {
            Prefs.remove(context, PKeys.USER_SERVERS);
        }

        public static void removeServer(Context context, Server server) {
            try {
                JSONArray array = Prefs.getJSONArray(context, PKeys.USER_SERVERS, new JSONArray());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    if (Objects.equals(obj.optString("name"), server.name)) {
                        array.remove(i);
                        break;
                    }
                }

                Prefs.putJSONArray(context, PKeys.USER_SERVERS, array);
            } catch (JSONException ex) {
                Logging.logMe(ex);
            }
        }

        @Override
        public String toString() {
            return name;
        }

        private JSONObject toJSON() throws JSONException {
            return new JSONObject()
                    .put("name", name)
                    .put("uri", uri.toString());
        }

        @Override
        public String getPrimaryText() {
            return name;
        }

        @Nullable
        @Override
        public String getSecondaryText() {
            return uri.toString();
        }
    }

    public class PollingThread extends Thread {
        private final WeakHashMap<String, IEventListener> listeners = new WeakHashMap<>();
        private final AtomicInteger exCount = new AtomicInteger(0);
        private boolean shouldStop = false;

        @Override
        public void run() {
            while (!shouldStop) {
                try {
                    HttpPost post = new HttpPost(server.uri.toString() + "LongPollServlet");
                    HttpResponse resp = client.execute(post, pollingContext);

                    StatusLine sl = resp.getStatusLine();
                    if (sl.getStatusCode() != HttpStatus.SC_OK)
                        throw new StatusCodeException(sl);

                    String json = EntityUtils.toString(resp.getEntity());
                    post.releaseConnection();

                    if (json.startsWith("{")) {
                        JSONObject obj = new JSONObject(json);
                        raiseException(obj);
                    } else if (json.startsWith("[")) {
                        JSONArray array = new JSONArray(json);
                        dispatchDone(CommonUtils.toTList(array, PollMessage.class));
                    }
                } catch (final IOException | JSONException | PYXException ex) {
                    dispatchEx(ex);
                }
            }
        }

        private void dispatchDone(final List<PollMessage> obj) {
            exCount.set(0);
            handler.post(new Runnable() {
                @Override
                public void run() {
                    for (IEventListener listener : listeners.values()) {
                        for (PollMessage message : obj) {
                            try {
                                listener.onPollMessage(message);
                            } catch (JSONException ex) {
                                dispatchEx(ex);
                            }
                        }
                    }
                }
            });
        }

        private void dispatchEx(final Exception ex) {
            exCount.getAndIncrement();
            if (exCount.get() > 5) {
                safeStop();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        for (IEventListener listener : listeners.values())
                            listener.onStoppedPolling();
                    }
                });
            }

            Logging.logMe(ex);
        }

        public void addListener(String tag, IEventListener listener) {
            this.listeners.put(tag, listener);
        }

        void safeStop() {
            shouldStop = true;
        }
    }
}
