package com.gianlu.pretendyourexyzzy.Main.OngoingGame;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.annotation.UiThread;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.DividerItemDecoration;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.gianlu.commonutils.Analytics.AnalyticsApplication;
import com.gianlu.commonutils.CommonUtils;
import com.gianlu.commonutils.Logging;
import com.gianlu.commonutils.Toaster;
import com.gianlu.commonutils.Tutorial.BaseTutorial;
import com.gianlu.commonutils.Tutorial.TutorialManager;
import com.gianlu.pretendyourexyzzy.Adapters.CardsAdapter;
import com.gianlu.pretendyourexyzzy.Adapters.PlayersAdapter;
import com.gianlu.pretendyourexyzzy.CardViews.GameCardView;
import com.gianlu.pretendyourexyzzy.Dialogs.CardImageZoomDialog;
import com.gianlu.pretendyourexyzzy.Dialogs.Dialogs;
import com.gianlu.pretendyourexyzzy.NetIO.Models.BaseCard;
import com.gianlu.pretendyourexyzzy.NetIO.Models.Card;
import com.gianlu.pretendyourexyzzy.NetIO.Models.CardsGroup;
import com.gianlu.pretendyourexyzzy.NetIO.Models.Game;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GameCards;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GameInfo;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GameInfoAndCards;
import com.gianlu.pretendyourexyzzy.NetIO.Models.GamePermalink;
import com.gianlu.pretendyourexyzzy.NetIO.Models.PollMessage;
import com.gianlu.pretendyourexyzzy.NetIO.Pyx;
import com.gianlu.pretendyourexyzzy.NetIO.PyxException;
import com.gianlu.pretendyourexyzzy.NetIO.PyxRequests;
import com.gianlu.pretendyourexyzzy.NetIO.RegisteredPyx;
import com.gianlu.pretendyourexyzzy.R;
import com.gianlu.pretendyourexyzzy.Starred.StarredCardsManager;
import com.gianlu.pretendyourexyzzy.Tutorial.Discovery;
import com.gianlu.pretendyourexyzzy.Tutorial.HowToPlayTutorial;
import com.gianlu.pretendyourexyzzy.Utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;

public class BestGameManager implements Pyx.OnEventListener {
    private final static String POLLING = BestGameManager.class.getName();
    private final Ui ui;
    private final Data data;
    private final Listener listener;
    private final RegisteredPyx pyx;
    private final Context context;

    public BestGameManager(Context context, Fragment fragment, ViewGroup layout, RegisteredPyx pyx, GameInfoAndCards bundle, GamePermalink perm, Listener listener, PlayersAdapter.Listener playersListener) {
        this.context = context;
        this.pyx = pyx;
        this.listener = listener;
        this.ui = new Ui(fragment, layout);
        this.data = new Data(bundle, perm, playersListener);

        this.pyx.polling().addListener(POLLING, this);
        this.data.setup();
    }

    @Nullable
    public String getLastRoundMetricsId() {
        if (data.lastRoundPermalink == null) return null;
        String[] split = data.lastRoundPermalink.split("/");
        return split[split.length - 1];
    }

    public void onDestroy() {
        this.pyx.polling().removeListener(POLLING);
    }

    @Override
    public void onPollMessage(@NonNull PollMessage msg) throws JSONException {
        if (msg.event != PollMessage.Event.CHAT && CommonUtils.isDebug())
            System.out.println(BestGameManager.class.getSimpleName() + ": " + msg.event.name() + " -> " + msg.obj);

        switch (msg.event) {
            case HAND_DEAL:
                data.handDeal(Card.list(msg.obj.getJSONArray("h")));
                break;
            case GAME_STATE_CHANGE:
                data.gameStateChanged(Game.Status.parse(msg.obj.getString("gs")), msg.obj);
                break;
            case GAME_PLAYER_INFO_CHANGE:
                data.gamePlayerInfoChanged(new GameInfo.Player(msg.obj.getJSONObject("pi")));
                break;
            case GAME_ROUND_COMPLETE:
                data.gameRoundComplete(msg.obj.getString("rw"), msg.obj.getInt("WC"), msg.obj.optString("rP", null), msg.obj.getInt("i"));
                break;
            case GAME_OPTIONS_CHANGED:
                data.gameOptionsChanged(new Game(msg.obj.getJSONObject("gi")));
                break;
            case HURRY_UP:
                ui.event(UiEvent.HURRY_UP);
                break;
            case GAME_PLAYER_JOIN:
                data.gamePlayerJoin(new GameInfo.Player(msg.obj.getString("n"), 0, GameInfo.PlayerStatus.IDLE));
                break;
            case GAME_PLAYER_LEAVE:
                data.gamePlayerLeave(msg.obj.getString("n"));
                break;
            case GAME_PLAYER_SKIPPED:
                ui.event(UiEvent.PLAYER_SKIPPED, msg.obj.getString("n"));
                break;
            case GAME_JUDGE_LEFT:
                data.gameJudgeLeft(msg.obj.getInt("i"));
                break;
            case GAME_JUDGE_SKIPPED:
                data.gameJudgeSkipped();
                break;
            case GAME_PLAYER_KICKED_IDLE:
                data.gamePlayerKickedIdle(msg.obj.getString("n"));
                break;
            case GAME_SPECTATOR_JOIN:
                data.gameSpectatorJoin(msg.obj.getString("n"));
                break;
            case GAME_SPECTATOR_LEAVE:
                data.gameSpectatorLeave(msg.obj.getString("n"));
                break;
            case KICKED:
            case BANNED:
                listener.shouldLeaveGame();
                break;
            case GAME_BLACK_RESHUFFLE:
            case GAME_WHITE_RESHUFFLE:
                break;
            case CARDCAST_REMOVE_CARDSET:
            case CARDCAST_ADD_CARDSET:
                break;
            case CHAT:
            case GAME_LIST_REFRESH:
            case NEW_PLAYER:
            case NOOP:
            case PLAYER_LEAVE:
            case KICKED_FROM_GAME_IDLE:
                break;
        }
    }

    @Override
    public void onStoppedPolling() {
        Toaster.with(context).message(R.string.failedLoading).show();
        pyx.request(PyxRequests.leaveGame(gid()), null);
        listener.shouldLeaveGame();
    }

    private void startGame() {
        pyx.request(PyxRequests.startGame(gid()), new Pyx.OnSuccess() {
            @Override
            public void onDone() {
                Toaster.with(context).message(R.string.gameStarted).show();
            }

            @Override
            public void onException(@NonNull Exception ex) {
                if (!(ex instanceof PyxException) || !ui.handleStartGameException((PyxException) ex))
                    Toaster.with(context).message(R.string.failedStartGame).ex(ex).show();
            }
        });
    }

    @NonNull
    public GameInfo gameInfo() {
        return data.info;
    }

    @NonNull
    public View getStartGameButton() {
        return ui.startGame;
    }

    @NonNull
    public String me() {
        return pyx.user().nickname;
    }

    @NonNull
    public String host() {
        return gameInfo().game.host;
    }

    public int gid() {
        return gameInfo().game.gid;
    }

    public boolean amHost() {
        return Objects.equals(host(), me());
    }

    private boolean amSpectator() {
        return gameInfo().game.spectators.contains(me());
    }

    private boolean amPlaying() {
        GameInfo.Player me = gameInfo().player(me());
        return me != null && me.status == GameInfo.PlayerStatus.PLAYING;
    }

    private enum UiEvent {
        YOU_JUDGE(R.string.game_youJudge, Kind.TEXT),
        SELECT_WINNING_CARD(R.string.game_selectWinningCard, Kind.TEXT),
        YOU_ROUND_WINNER(R.string.game_youRoundWinner_long, R.string.game_youRoundWinner_short),
        SPECTATOR_TEXT(R.string.game_spectator, Kind.TEXT),
        YOU_GAME_HOST(R.string.game_youGameHost, Kind.TEXT),
        WAITING_FOR_ROUND_TO_END(R.string.game_waitingForRoundToEnd, Kind.TEXT),
        WAITING_FOR_START(R.string.game_waitingForStart, Kind.TEXT),
        JUDGE_LEFT(R.string.game_judgeLeft_long, R.string.game_judgeLeft_short),
        IS_JUDGING(R.string.game_isJudging, Kind.TEXT),
        ROUND_WINNER(R.string.game_roundWinner_long, R.string.game_roundWinner_short),
        WAITING_FOR_OTHER_PLAYERS(R.string.game_waitingForPlayers, Kind.TEXT),
        PLAYER_SKIPPED(R.string.game_playerSkipped, Kind.TOAST),
        PICK_CARDS(R.string.game_pickCards, Kind.TEXT),
        JUDGE_SKIPPED(R.string.game_judgeSkipped, Kind.TOAST),
        GAME_WINNER(R.string.game_gameWinner_long, R.string.game_gameWinner_short),
        YOU_GAME_WINNER(R.string.game_youGameWinner_long, R.string.game_youGameWinner_short),
        NOT_YOUR_TURN(R.string.game_notYourTurn, Kind.TOAST),
        HURRY_UP(R.string.hurryUp, Kind.TOAST),
        PLAYER_KICKED(R.string.game_playerKickedIdle, Kind.TOAST),
        SPECTATOR_TOAST(R.string.game_spectator, Kind.TOAST);

        private final int toast;
        private final int text;
        private final Kind kind;

        UiEvent(@StringRes int text, Kind kind) {
            this.text = text;
            this.kind = kind;
            this.toast = 0;
        }

        UiEvent(@StringRes int text, @StringRes int toast) {
            this.toast = toast;
            this.text = text;
            this.kind = Kind.BOTH;
        }

        public enum Kind {
            TOAST,
            TEXT,
            BOTH
        }
    }

    public interface Listener extends GameCardView.TextSelectionListener {
        void shouldLeaveGame();

        void showDialog(@NonNull AlertDialog.Builder dialog);

        void updateActivityTitle();

        void showDialog(@NonNull DialogFragment dialog);

        @Nullable
        FragmentActivity getActivity();
    }

    private class Data implements CardsAdapter.Listener {
        private final GameInfo info;
        private final CardsAdapter handAdapter;
        private final CardsAdapter tableAdapter;
        private final PlayersAdapter playersAdapter;
        private final GamePermalink perm;
        private int judgeIndex = 0;
        private String lastRoundPermalink = null;

        Data(GameInfoAndCards bundle, GamePermalink perm, PlayersAdapter.Listener listener) {
            this.perm = perm;
            this.info = bundle.info;
            GameCards cards = bundle.cards;

            playersAdapter = new PlayersAdapter(context, info.players, listener);
            ui.playersList.setAdapter(playersAdapter);

            handAdapter = new CardsAdapter(context, GameCardView.Action.SELECT, GameCardView.Action.TOGGLE_STAR, this);
            handAdapter.addCards(cards.hand);

            tableAdapter = new CardsAdapter(context, GameCardView.Action.SELECT, GameCardView.Action.TOGGLE_STAR, this);
            tableAdapter.setCardGroups(cards.whiteCards, cards.blackCard);

            ui.blackCard(cards.blackCard);
        }

        public void setup() { // Called ONLY from constructor
            for (int i = 0; i < info.players.size(); i++) {
                GameInfo.Player player = info.players.get(i);
                switch (player.status) {
                    case JUDGE:
                    case JUDGING:
                        judgeIndex = i;
                        break;
                    case HOST:
                    case IDLE:
                    case PLAYING:
                    case WINNER:
                    case SPECTATOR:
                        break;
                }
            }

            if (amSpectator()) {
                ui.showTableCards(false);
                ui.event(UiEvent.SPECTATOR_TEXT);
            } else {
                GameInfo.Player me = info.player(me());
                if (me != null) {
                    switch (me.status) {
                        case JUDGE:
                            ui.event(UiEvent.YOU_JUDGE);
                            ui.showTableCards(true);
                            break;
                        case JUDGING:
                            ui.event(UiEvent.SELECT_WINNING_CARD);
                            ui.showTableCards(true);
                            break;
                        case PLAYING:
                            BaseCard bc = ui.blackCard();
                            if (bc != null) ui.event(UiEvent.PICK_CARDS, bc.numPick());
                            ui.showHandCards(true);
                            break;
                        case IDLE:
                            ui.showTableCards(false);

                            if (info.game.status == Game.Status.JUDGING) {
                                GameInfo.Player judge = info.players.get(judgeIndex);
                                ui.event(UiEvent.IS_JUDGING, judge.name);
                            } else if (info.game.status == Game.Status.LOBBY) {
                                ui.event(UiEvent.WAITING_FOR_START);
                            } else {
                                ui.event(UiEvent.WAITING_FOR_ROUND_TO_END);
                            }
                            break;
                        case WINNER:
                            ui.event(UiEvent.YOU_GAME_WINNER);
                            break;
                        case HOST:
                            ui.event(UiEvent.YOU_GAME_HOST);
                            break;
                        case SPECTATOR:
                            ui.showTableCards(false);
                            tableAdapter.setSelectable(false);
                            break;
                    }
                }
            }

            ui.setStartGameVisible(info.game.status == Game.Status.LOBBY && Objects.equals(host(), me()));
        }

        public void gameStateChanged(@NonNull Game.Status status, @NonNull JSONObject obj) throws JSONException {
            info.game.status = status;
            if (obj.has("gp")) perm.gamePermalink = obj.getString("gp");

            ui.setStartGameVisible(status == Game.Status.LOBBY && Objects.equals(host(), me()));
            switch (status) {
                case PLAYING:
                    playingState(new Card(obj.getJSONObject("bc")), obj.getInt("Pt"));
                    nextRound();
                    break;
                case JUDGING:
                    judgingState(CardsGroup.list(obj.getJSONArray("wc")), obj.getInt("Pt"));
                    break;
                case LOBBY:
                    ui.event(UiEvent.WAITING_FOR_START);
                    ui.blackCard(null);
                    tableAdapter.clear();
                    handAdapter.clear();
                    ui.showTableCards(false);
                    break;
                case DEALING:
                case ROUND_OVER:
                    // Never called
                    break;
            }
        }

        public void nextRound() {
            judgeIndex++;
            if (judgeIndex >= info.players.size()) judgeIndex = 0;

            for (int i = 0; i < info.players.size(); i++) {
                GameInfo.Player player = info.players.get(i);
                if (i == judgeIndex) player.status = GameInfo.PlayerStatus.JUDGE;
                else player.status = GameInfo.PlayerStatus.PLAYING;
                gamePlayerInfoChanged(player); // Allowed, not notified by server
            }

            tableAdapter.clear();
        }

        public void handDeal(List<Card> cards) {
            handAdapter.addCards(cards);
        }

        private void playingState(@NonNull Card blackCard, int playTime) {
            ui.blackCard(blackCard);
            ui.resetTimer(playTime);
        }

        private void judgingState(List<CardsGroup> cards, int playTime) {
            tableAdapter.setCardGroups(cards, null);
            ui.resetTimer(playTime);
        }

        public void gameRoundComplete(String roundWinner, int winningCard, @Nullable String roundPermalink, int intermission) {
            if (Objects.equals(roundWinner, me())) ui.event(UiEvent.YOU_ROUND_WINNER);
            else ui.event(UiEvent.ROUND_WINNER, roundWinner);

            lastRoundPermalink = roundPermalink;

            tableAdapter.notifyWinningCard(winningCard);
            ui.resetTimer(intermission);
        }

        public void gamePlayerInfoChanged(@NonNull GameInfo.Player player) {
            playersAdapter.playerChanged(player);

            switch (player.status) {
                case JUDGING:
                    if (Objects.equals(player.name, me())) {
                        ui.showTableCards(true);
                        ui.event(UiEvent.SELECT_WINNING_CARD);
                    } else {
                        ui.event(UiEvent.IS_JUDGING, player.name);
                    }
                    break;
                case JUDGE:
                    if (Objects.equals(player.name, me())) {
                        ui.showTableCards(false);

                        if (info.game.status != Game.Status.JUDGING) // Called after #gameRoundComplete()
                            ui.event(UiEvent.YOU_JUDGE);
                    }

                    judgeIndex = Utils.indexOf(info.players, player.name); // Redundant, but for safety...
                    break;
                case IDLE:
                    if (Objects.equals(player.name, me())) {
                        ui.showTableCards(false);

                        if (info.game.status != Game.Status.JUDGING)
                            ui.event(UiEvent.WAITING_FOR_OTHER_PLAYERS);
                    }

                    if (info.game.status == Game.Status.PLAYING) {
                        BaseCard bc = ui.blackCard();
                        if (bc != null) tableAdapter.addBlankCards(bc);
                    }
                    break;
                case PLAYING:
                    if (Objects.equals(player.name, me())) {
                        ui.showHandCards(true);

                        BaseCard bc = ui.blackCard();
                        if (bc != null) ui.event(UiEvent.PICK_CARDS, bc.numPick());

                        ui.tryShowingTutorials();
                    }
                    break;
                case WINNER:
                    if (player.name.equals(me())) ui.event(UiEvent.YOU_GAME_WINNER);
                    else ui.event(UiEvent.GAME_WINNER, player.name);
                    break;
                case HOST:
                    if (player.name.equals(me())) ui.event(UiEvent.YOU_GAME_HOST);
                    break;
                case SPECTATOR:
                    ui.showTableCards(false);
                    break;
            }
        }

        public void gamePlayerJoin(@NonNull GameInfo.Player player) {
            info.newPlayer(player);
            playersAdapter.newPlayer(player);
        }

        public void gamePlayerLeave(@NonNull String nick) {
            info.removePlayer(nick);
            playersAdapter.removePlayer(nick);

            int pos = Utils.indexOf(info.players, nick);
            if (pos < judgeIndex) judgeIndex--;

            if (Objects.equals(host(), nick)) {
                if (info.players.isEmpty()) {
                    listener.shouldLeaveGame();
                } else {
                    GameInfo.Player newHost = info.players.get(0);
                    info.game.host = newHost.name;
                    playersAdapter.playerChanged(newHost);
                    listener.updateActivityTitle();
                }
            }
        }

        @Nullable
        @Override
        public RecyclerView getCardsRecyclerView() {
            return ui.whiteCardsList;
        }

        @Override
        public void onCardAction(@NonNull GameCardView.Action action, @NonNull CardsGroup group, @NonNull BaseCard card) {
            if (action == GameCardView.Action.SELECT) {
                GameInfo.Player me = info.player(me());
                if (me != null) {
                    if (me.status == GameInfo.PlayerStatus.PLAYING && info.game.status == Game.Status.PLAYING) {
                        ui.playCard(card);
                    } else if ((me.status == GameInfo.PlayerStatus.JUDGE || me.status == GameInfo.PlayerStatus.JUDGING) && info.game.status == Game.Status.JUDGING) {
                        ui.judgeSelectCard(card);
                    } else {
                        if (amSpectator()) ui.event(UiEvent.SPECTATOR_TOAST);
                        else ui.event(UiEvent.NOT_YOUR_TURN);
                    }
                }
            } else if (action == GameCardView.Action.TOGGLE_STAR) {
                BaseCard bc = ui.blackCard();
                if (bc != null && StarredCardsManager.addCard(context, new StarredCardsManager.StarredCard(bc, group)))
                    Toaster.with(context).message(R.string.addedCardToStarred).show();
            } else if (action == GameCardView.Action.SELECT_IMG) {
                listener.showDialog(CardImageZoomDialog.get(card));
            }
        }

        @Override
        public void onTextSelected(@NonNull String text) {
            listener.onTextSelected(text);
        }

        public void gameOptionsChanged(@NonNull Game game) {
            this.info.game.options = game.options;
            this.info.game.host = game.host;
            listener.updateActivityTitle();
        }

        public void gameJudgeLeft(int intermission) {
            if (judgeIndex != -1) {
                GameInfo.Player judge = info.players.get(judgeIndex);
                ui.event(UiEvent.JUDGE_LEFT, judge.name);
                judgeIndex--; // Will be incremented by #nextRound()
            }

            tableAdapter.clear();
            ui.showTableCards(false);
            ui.resetTimer(intermission);
        }

        public void gameJudgeSkipped() {
            if (judgeIndex != -1) {
                GameInfo.Player judge = info.players.get(judgeIndex);
                ui.event(UiEvent.JUDGE_SKIPPED, judge.name);
            }
        }

        public void removeFromHand(@NonNull BaseCard card) {
            handAdapter.removeCard(card);
        }

        public void gameSpectatorJoin(String nick) {
            info.newSpectator(nick);
        }

        public void gameSpectatorLeave(String nick) {
            info.removeSpectator(nick);
        }

        public void gamePlayerKickedIdle(String nick) {
            ui.event(UiEvent.PLAYER_KICKED, nick);
            if (Objects.equals(nick, me())) listener.shouldLeaveGame();
        }
    }

    private class Ui implements TutorialManager.Listener {
        private final FloatingActionButton startGame;
        private final GameCardView blackCard;
        private final TextView instructions;
        private final RecyclerView whiteCardsList;
        private final RecyclerView playersList;
        private final TextView time;
        private final Timer timer = new Timer();
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Fragment fragment;
        private final TutorialManager tutorialManager;
        private TimeTask currentTask;

        Ui(Fragment fragment, ViewGroup layout) {
            this.fragment = fragment;
            tutorialManager = new TutorialManager(context, this, Discovery.HOW_TO_PLAY);

            blackCard = layout.findViewById(R.id.gameLayout_blackCard);
            blackCard.setTextSelectionListener(listener);

            instructions = layout.findViewById(R.id.gameLayout_instructions);
            time = layout.findViewById(R.id.gameLayout_time);

            startGame = layout.findViewById(R.id.gameLayout_startGame);
            startGame.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startGame();
                }
            });

            whiteCardsList = layout.findViewById(R.id.gameLayout_whiteCards);
            whiteCardsList.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false));

            playersList = layout.findViewById(R.id.gameLayout_players);
            playersList.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
            playersList.addItemDecoration(new DividerItemDecoration(context, DividerItemDecoration.VERTICAL));
        }

        public void resetTimer(int millis) {
            if (currentTask != null) currentTask.cancel();
            currentTask = new TimeTask(millis / 1000);
            timer.scheduleAtFixedRate(currentTask, 0, 1000);
        }

        /**
         * @return Whether the exception has been handled
         */
        public boolean handleStartGameException(PyxException ex) {
            if (Objects.equals(ex.errorCode, "nep")) {
                Toaster.with(context).message(R.string.notEnoughPlayers).show();
                return true;
            } else if (Objects.equals(ex.errorCode, "nec")) {
                try {
                    listener.showDialog(Dialogs.notEnoughCards(context, ex));
                    return true;
                } catch (JSONException exx) {
                    Logging.log(exx);
                    return false;
                }
            } else {
                return false;
            }
        }

        @UiThread
        public void showTableCards(boolean selectable) {
            data.tableAdapter.setSelectable(selectable);
            if (whiteCardsList.getAdapter() != data.tableAdapter)
                whiteCardsList.swapAdapter(data.tableAdapter, true);
            else
                data.tableAdapter.notifyDataSetChanged();
        }

        @UiThread
        public void showHandCards(boolean selectable) {
            data.handAdapter.setSelectable(selectable);
            if (whiteCardsList.getAdapter() != data.handAdapter)
                whiteCardsList.swapAdapter(data.handAdapter, true);
            else
                data.handAdapter.notifyDataSetChanged();
        }

        public void blackCard(@Nullable Card card) {
            blackCard.setCard(card);
        }

        public void event(@NonNull UiEvent ev, Object... args) {
            switch (ev.kind) {
                case BOTH:
                    uiToast(ev.toast, args);
                    if (ev == UiEvent.SPECTATOR_TEXT || !amSpectator())
                        uiText(ev.text, args);
                    break;
                case TOAST:
                    uiToast(ev.text, args);
                    break;
                case TEXT:
                    if (ev == UiEvent.SPECTATOR_TEXT || !amSpectator())
                        uiText(ev.text, args);
                    break;
            }
        }

        public void judgeSelectCard(@NonNull final BaseCard card) {
            listener.showDialog(Dialogs.confirmation(context, new Dialogs.OnConfirmed() {
                @Override
                public void onConfirmed() {
                    judgeSelectCardInternal(card);
                }
            }));
        }

        public void playCard(@NonNull final BaseCard card) {
            if (card.writeIn()) {
                listener.showDialog(Dialogs.askText(context, new Dialogs.OnText() {
                    @Override
                    public void onText(@NonNull String text) {
                        playCardInternal(card, text);
                    }
                }));
            } else {
                listener.showDialog(Dialogs.confirmation(context, new Dialogs.OnConfirmed() {
                    @Override
                    public void onConfirmed() {
                        playCardInternal(card, null);
                    }
                }));
            }
        }

        //*****************//
        // Private methods //
        //*****************//

        private void judgeSelectCardInternal(@NonNull BaseCard card) {
            pyx.request(PyxRequests.judgeCard(gid(), card.id()), new Pyx.OnSuccess() {
                @Override
                public void onDone() {
                    AnalyticsApplication.sendAnalytics(context, Utils.ACTION_JUDGE_CARD);
                }

                @Override
                public void onException(@NonNull Exception ex) {
                    Toaster.with(context).message(R.string.failedJudging).ex(ex).show();
                }
            });
        }

        private void playCardInternal(@NonNull final BaseCard card, @Nullable final String customText) {
            pyx.request(PyxRequests.playCard(gid(), card.id(), customText), new Pyx.OnSuccess() {
                @Override
                public void onDone() {
                    data.removeFromHand(card);

                    if (customText == null)
                        AnalyticsApplication.sendAnalytics(context, Utils.ACTION_PLAY_CARD);
                    else
                        AnalyticsApplication.sendAnalytics(context, Utils.ACTION_PLAY_CUSTOM_CARD);
                }

                @Override
                public void onException(@NonNull Exception ex) {
                    Toaster.with(context).message(R.string.failedPlayingCard).ex(ex).show();
                }
            });
        }

        private void uiToast(@StringRes int text, Object... args) {
            Toaster.with(context).message(text, args).show();
        }

        private void uiText(@StringRes int text, Object... args) {
            instructions.setText(context.getString(text, args));
        }

        @Nullable
        public BaseCard blackCard() {
            return blackCard.getCard();
        }

        public void setStartGameVisible(boolean set) {
            startGame.setVisibility(set ? View.VISIBLE : View.GONE);
        }

        @Override
        public boolean canShow(@NonNull BaseTutorial tutorial) {
            return tutorial instanceof HowToPlayTutorial && CommonUtils.isVisible(fragment)
                    && amPlaying() && gameInfo().game.status == Game.Status.PLAYING;
        }

        @Override
        public boolean buildSequence(@NonNull BaseTutorial tutorial) {
            return ((HowToPlayTutorial) tutorial).buildSequence(blackCard, whiteCardsList, playersList);
        }

        public void tryShowingTutorials() {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    tutorialManager.tryShowingTutorials(listener.getActivity());
                }
            });
        }

        private class TimeTask extends TimerTask {
            private int count;

            TimeTask(int time) {
                this.count = time;
            }

            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        time.setText(String.valueOf(count));
                    }
                });

                if (count <= 0) cancel();
                else count--;
            }
        }
    }
}
