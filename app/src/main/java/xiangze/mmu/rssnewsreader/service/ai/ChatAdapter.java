package xiangze.mmu.rssnewsreader.service.ai;

import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import xiangze.mmu.rssnewsreader.R;
import xiangze.mmu.rssnewsreader.data.ai.Message;

public class ChatAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private static final int TYPE_USER = 1;
    private static final int TYPE_ASSISTANT = 2;

    private List<Message> messages;

    public ChatAdapter(List<Message> messages) {
        this.messages = messages;
    }

    @Override
    public int getItemViewType(int position) {
        Message message = messages.get(position);
        return message.role.equals("user") ? TYPE_USER : TYPE_ASSISTANT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());

        if (viewType == TYPE_USER) {
            View view = inflater.inflate(R.layout.item_message_user, parent, false);
            return new UserMessageViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_message_assistant, parent, false);
            return new AssistantMessageViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Message message = messages.get(position);

        if (holder instanceof AssistantMessageViewHolder) {
            AssistantMessageViewHolder assistantHolder = (AssistantMessageViewHolder) holder;
            SpannableString formattedText = SimpleMarkdownParser.parseSimpleMarkdown(message.content);
            assistantHolder.messageText.setText(formattedText);
            assistantHolder.messageText.setMovementMethod(LinkMovementMethod.getInstance()); // For clickable links
        } else {
            // User messages - no formatting needed
            ((UserMessageViewHolder) holder).messageText.setText(message.content);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class UserMessageViewHolder extends RecyclerView.ViewHolder {
        TextView messageText;

        public UserMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.messageText);
        }

        public void bind(Message message) {
            messageText.setText(message.content);
        }
    }

    static class AssistantMessageViewHolder extends RecyclerView.ViewHolder {
        TextView messageText;

        public AssistantMessageViewHolder(@NonNull View itemView) {
            super(itemView);
            messageText = itemView.findViewById(R.id.messageText);
        }

        public void bind(Message message) {
            messageText.setText(message.content);
        }
    }
}