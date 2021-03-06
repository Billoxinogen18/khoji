package com.aadimator.khoji.activities;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.format.DateFormat;
import android.util.Log;

import kotlin.Pair;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.aadimator.khoji.R;
import com.aadimator.khoji.common.Constant;
import com.aadimator.khoji.common.GlideApp;
import com.aadimator.khoji.common.Utilities;
import com.aadimator.khoji.models.ChatMessage;
import com.aadimator.khoji.models.User;
import com.firebase.ui.database.FirebaseRecyclerAdapter;
import com.firebase.ui.database.FirebaseRecyclerOptions;
import com.github.kittinunf.fuel.Fuel;
import com.github.kittinunf.fuel.core.FuelError;
import com.github.kittinunf.fuel.core.FuelManager;
import com.github.kittinunf.fuel.core.Handler;
import com.github.kittinunf.fuel.core.Method;
import com.github.kittinunf.fuel.core.Request;
import com.github.kittinunf.fuel.core.Response;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.Optional;

public class ChatActivity extends AppCompatActivity {

    public static final String TAG = ChatActivity.class.getSimpleName();
    public static final String BUNDLE_ROOM_ID = "com.aadimator.khoji.activities.room_id";
    public static final String BUNDLE_CURRENT_USER = "com.aadimator.khoji.activities.current_user";
    public static final String BUNDLE_OTHER_USER_ID = "com.aadimator.khoji.activities.other_user_ID";

    @BindView(R.id.edit_text_message)
    EditText mEditTextMessage;
    @BindView(R.id.rv_chat_messages)
    RecyclerView mRecyclerViewMessages;
    private int MY_MESSAGE = 0;
    private int THEIR_MESSAGE = 1;
    private String mRoomId = "";
    private String mCurrentUserId = "";
    private String mOtherUserId = "";
    private FirebaseUser mCurrentUser;
    private User mOtherUser;
    private FirebaseRecyclerAdapter mRecyclerAdapter;
    private boolean isBot = false;

    private FuelManager mFuelManager;

    public static Intent newIntent(Context context, String other_user_id) {
        Intent intent = new Intent(context, ChatActivity.class);
        intent.putExtra(BUNDLE_OTHER_USER_ID, other_user_id);
        return intent;
    }

    public static String getRoomId(String uid1, String uid2) {
        return (uid1.compareTo(uid2) > 0) ?
                uid1 + '_' + uid2 :
                uid2 + '_' + uid1;
    }

    @OnClick(R.id.button_send_message)
    public void sendMessage(View view) {
        if (!mEditTextMessage.getText().toString().matches("")) {
            FirebaseDatabase.getInstance()
                    .getReference(Constant.FIREBASE_URL_CHATS)
                    .child(mRoomId)
                    .push()
                    .setValue(
                            new ChatMessage(
                                    mEditTextMessage.getText().toString(),
                                    mCurrentUserId,
                                    mCurrentUser.getDisplayName()
                            )
                    );

            if (isBot) {
                getBotResponse(mEditTextMessage.getText().toString());
            }

            // Clear the input
            mEditTextMessage.setText("");
        }
    }

    private void getBotResponse(String query) {
        List<Pair<String, String>> params = new ArrayList<>();
        params.add(new Pair<>("query", query));

        mFuelManager.request(Method.GET, "/query", params).responseString(new Handler<String>() {
            @Override
            public void success(Request request, Response response, String s) {
//                        Log.i(TAG, "Response: " + request.toString());
//                        Log.i(TAG, "Request: " + response.toString());
//                        Log.i(TAG, "String: " + s);
                JsonObject result = new JsonParser().parse(s).getAsJsonObject();
                JsonArray messages = result.getAsJsonObject("result")
                        .getAsJsonObject("fulfillment")
                        .getAsJsonArray("messages");
                for (JsonElement element : messages) {
                    String message = element.getAsJsonObject().get("speech").getAsString();
                    Log.i(TAG, "Message #" + message);
                    FirebaseDatabase.getInstance()
                            .getReference(Constant.FIREBASE_URL_CHATS)
                            .child(mRoomId)
                            .push()
                            .setValue(
                                    new ChatMessage(
                                            message,
                                            Constant.BOT_UID,
                                            mOtherUser.getName()
                                    )
                            );
                }
            }

            @Override
            public void failure(Request request, Response response, FuelError fuelError) {
                Log.d(TAG, "Error getting bot response." + fuelError.toString());
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        ButterKnife.bind(this);


        mCurrentUser = FirebaseAuth.getInstance().getCurrentUser();
        assert mCurrentUser != null;
        mCurrentUserId = mCurrentUser.getUid();

        if (Objects.requireNonNull(getIntent().getExtras()).getString(BUNDLE_OTHER_USER_ID) != null) {
            mOtherUserId = getIntent().getExtras().getString(BUNDLE_OTHER_USER_ID);
        }

        mRoomId = getRoomId(mCurrentUserId, mOtherUserId);
        Log.i(TAG, "Room id: " + mRoomId);

        if (mOtherUserId.equals(Constant.BOT_UID)) {
            isBot = true;
            setBotAPI();
        }

        getOtherUser();

        mRecyclerAdapter = createRecyclerAdapater();
        LinearLayoutManager linearLayoutManager = new LinearLayoutManager(this);

        // scroll to the latest message.
        mRecyclerAdapter.registerAdapterDataObserver(new RecyclerView.AdapterDataObserver() {
            @Override
            public void onItemRangeInserted(int positionStart, int itemCount) {
                linearLayoutManager.smoothScrollToPosition
                        (
                                mRecyclerViewMessages,
                                null,
                                mRecyclerAdapter.getItemCount()
                        );
            }
        });
        mRecyclerViewMessages.setLayoutManager(linearLayoutManager);
        mRecyclerViewMessages.setAdapter(mRecyclerAdapter);
    }

    private void setBotAPI() {
        mFuelManager = new FuelManager();
        Map<String, String> baseHeader = new HashMap<>();
        baseHeader.put("Authorization", "Bearer " + Constant.DIALOGEFLOW_ACCESS_TOKEN);
        mFuelManager.setBaseHeaders(baseHeader);

        mFuelManager.setBasePath("https://api.dialogflow.com/v1/");
        Log.d(TAG, "FuelManager Base Path : " + mFuelManager.getBasePath());

        List<Pair<String, String>> baseParams = new ArrayList<>();
        baseParams.add(new Pair<>("v", "20150910"));
        baseParams.add(new Pair<>("sessionId", UUID.randomUUID().toString()));
        baseParams.add(new Pair<>("lang", "en"));
        mFuelManager.setBaseParams(baseParams);
    }

    @Override
    public void onStart() {
        super.onStart();
        mRecyclerAdapter.startListening();
    }

    @Override
    public void onStop() {
        super.onStop();
        mRecyclerAdapter.stopListening();
    }

    private void getOtherUser() {
        FirebaseDatabase.getInstance().getReference(Constant.FIREBASE_URL_USERS)
                .child(mOtherUserId)
                .addListenerForSingleValueEvent(new ValueEventListener() {
                    @Override
                    public void onDataChange(DataSnapshot dataSnapshot) {
                        mOtherUser = dataSnapshot.getValue(User.class);
                        Log.i(TAG, mOtherUser.getEmail());
                        getSupportActionBar().setTitle(mOtherUser.getName());
                    }

                    @Override
                    public void onCancelled(DatabaseError databaseError) {
                        Log.d(TAG, "Couldn't fetch Other user's details.");
                    }
                });
    }

    private FirebaseRecyclerAdapter createRecyclerAdapater() {

        DatabaseReference dataRef = FirebaseDatabase
                .getInstance()
                .getReference(Constant.FIREBASE_URL_CHATS)
                .child(mRoomId);

        Query keyQuery = dataRef.limitToLast(20);

        FirebaseRecyclerOptions<ChatMessage> options =
                new FirebaseRecyclerOptions.Builder<ChatMessage>()
                        .setQuery(keyQuery, ChatMessage.class)
                        .build();

        return new FirebaseRecyclerAdapter<ChatMessage, ChatActivity.ChatHolder>(options) {
            @Override
            public ChatActivity.ChatHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                View view;
                if (viewType == MY_MESSAGE) {
                    view = LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.my_message, parent, false);
                } else {
                    view = LayoutInflater.from(parent.getContext())
                            .inflate(R.layout.their_message, parent, false);
                }

                return new ChatActivity.ChatHolder(view);
            }

            @Override
            public int getItemViewType(int position) {
                return getItem(position).getUserId().equals(mCurrentUserId) ? MY_MESSAGE : THEIR_MESSAGE;
            }

            @Override
            protected void onBindViewHolder(@NonNull ChatActivity.ChatHolder holder, final int position, @NonNull final ChatMessage model) {
                if (getItemViewType(position) == MY_MESSAGE) {
                    holder.mTextViewBody.setText(model.getText());
                } else {
                    holder.mTextViewSender.setText(model.getUserName());
//                    holder.mTextViewTime.setText(DateFormat.format("dd-MM-yyyy (HH:mm:ss)",
//                            model.getTime()));
                    holder.mTextViewBody.setText(model.getText());
//                    GradientDrawable drawable = (GradientDrawable) holder.mViewAvatar.getBackground();
//                    drawable.setColor(Color.parseColor(Utilities.getRandomColor()));
                    if (mOtherUser != null) {
                        GlideApp.with(getApplicationContext())
                                .load(mOtherUser.getPhotoUrl())
                                .placeholder(R.drawable.user_avatar)
                                .circleCrop()
                                .into(holder.mViewAvatar);
                    }
                }

//                holder.mRelativeLayoutMessageBox.setBackgroundColor(Color.BLUE);
            }

            @Override
            public void onError(@NonNull DatabaseError error) {
                super.onError(error);
                Log.i(TAG, "Error getting data");
            }
        };
    }

    class ChatHolder extends RecyclerView.ViewHolder {


        @BindView(R.id.message_avatar)
        @Nullable
        ImageView mViewAvatar;

        @BindView(R.id.message_sender)
        @Nullable
        TextView mTextViewSender;

        @BindView(R.id.message_body)
        TextView mTextViewBody;
//        @BindView(R.id.rl_message_box)
//        RelativeLayout mRelativeLayoutMessageBox;

        ChatHolder(View itemView) {
            super(itemView);
            ButterKnife.bind(this, itemView);
        }
    }
}
