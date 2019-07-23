package com.wire.bots.sdk.server.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.InvalidProtocolBufferException;
import com.waz.model.Messages;
import com.wire.bots.cryptobox.CryptoException;
import com.wire.bots.sdk.ClientRepo;
import com.wire.bots.sdk.MessageHandlerBase;
import com.wire.bots.sdk.WireClient;
import com.wire.bots.sdk.server.GenericMessageProcessor;
import com.wire.bots.sdk.server.model.Conversation;
import com.wire.bots.sdk.server.model.Member;
import com.wire.bots.sdk.server.model.Payload;
import com.wire.bots.sdk.server.model.SystemMessage;
import com.wire.bots.sdk.tools.AuthValidator;
import com.wire.bots.sdk.tools.Logger;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public abstract class MessageResourceBase {
    private final AuthValidator validator;
    private final ClientRepo repo;
    private final MessageHandlerBase handler;

    public MessageResourceBase(MessageHandlerBase handler, AuthValidator validator, ClientRepo repo) {
        this.handler = handler;
        this.validator = validator;
        this.repo = repo;
    }

    protected void handleMessage(UUID id, Payload payload, WireClient client) throws Exception {
        if (Logger.getLevel() == Level.FINE) {
            ObjectMapper objectMapper = new ObjectMapper();
            Logger.debug(objectMapper.writeValueAsString(payload));
        }

        Payload.Data data = payload.data;
        UUID botId = UUID.fromString(client.getId());

        switch (payload.type) {
            case "conversation.otr-message-add": {
                UUID from = payload.from;

                Logger.debug("conversation.otr-message-add: bot: %s from: %s:%s", botId, from, data.sender);

                GenericMessageProcessor processor = new GenericMessageProcessor(client, handler);

                Messages.GenericMessage message = decrypt(client, payload);

                boolean process = processor.process(from,
                        data.sender,
                        payload.convId,
                        payload.time,
                        message);

                if (process) {
                    UUID messageId = UUID.fromString(message.getMessageId());
                    processor.cleanUp(messageId);
                }

                handler.onEvent(client, payload.from, message);
            }
            break;
            case "conversation.member-join": {
                Logger.debug("conversation.member-join: bot: %s", botId);

                // Check if this bot got added to the conversation
                List<UUID> participants = data.userIds;
                if (participants.remove(botId)) {
                    SystemMessage systemMessage = getSystemMessage(id, payload);
                    systemMessage.conversation = client.getConversation();
                    systemMessage.type = "conversation.create"; //hack the type

                    handler.onNewConversation(client, systemMessage);
                    return;
                }

                // Check if we still have some prekeys available. Upload new prekeys if needed
                handler.validatePreKeys(client, participants.size());

                SystemMessage systemMessage = getSystemMessage(id, payload);
                systemMessage.users = data.userIds;

                handler.onMemberJoin(client, systemMessage);
            }
            break;
            case "conversation.member-leave": {
                Logger.debug("conversation.member-leave: bot: %s", botId);

                SystemMessage systemMessage = getSystemMessage(id, payload);
                systemMessage.users = data.userIds;

                // Check if this bot got removed from the conversation
                List<UUID> participants = data.userIds;
                if (participants.remove(botId)) {
                    handler.onBotRemoved(botId, systemMessage);
                    repo.purgeBot(botId);
                    return;
                }

                if (!participants.isEmpty()) {
                    handler.onMemberLeave(client, systemMessage);
                }
            }
            break;
            case "conversation.delete": {
                Logger.debug("conversation.delete: bot: %s", botId);
                SystemMessage systemMessage = getSystemMessage(id, payload);

                // Cleanup
                handler.onBotRemoved(botId, systemMessage);
                repo.purgeBot(botId);
            }
            break;
            case "conversation.create": {
                Logger.debug("conversation.create: bot: %s", botId);

                SystemMessage systemMessage = getSystemMessage(id, payload);
                if (systemMessage.conversation.members != null) {
                    Member self = new Member();
                    self.id = botId;
                    systemMessage.conversation.members.add(self);
                }

                handler.onNewConversation(client, systemMessage);
            }
            break;
            case "conversation.rename": {
                Logger.debug("conversation.rename: bot: %s", botId);

                SystemMessage systemMessage = getSystemMessage(id, payload);

                handler.onConversationRename(client, systemMessage);
            }
            break;
            // UserMode code starts here
            case "user.connection": {
                Payload.Connection connection = payload.connection;
                Logger.debug("user.connection: bot: %s, from: %s to: %s status: %s",
                        botId,
                        connection.from,
                        connection.to,
                        connection.status);

                boolean accepted = handler.onConnectRequest(client, connection.from, connection.to, connection.status);
                if (accepted) {
                    Conversation conversation = new Conversation();
                    conversation.id = connection.convId;
                    SystemMessage systemMessage = new SystemMessage();
                    systemMessage.id = id;
                    systemMessage.from = connection.from;
                    systemMessage.type = payload.type;
                    systemMessage.conversation = conversation;

                    handler.onNewConversation(client, systemMessage);
                }
            }
            break;
            // UserMode code ends here
            default:
                Logger.debug("Unknown event: %s", payload.type);
                break;
        }
    }

    private SystemMessage getSystemMessage(UUID messageId, Payload payload) {
        SystemMessage systemMessage = new SystemMessage();
        systemMessage.id = messageId;
        systemMessage.from = payload.from;
        systemMessage.time = payload.time;
        systemMessage.type = payload.type;
        systemMessage.convId = payload.convId;

        systemMessage.conversation = new Conversation();
        systemMessage.conversation.id = payload.convId;
        systemMessage.conversation.creator = payload.data.creator;
        systemMessage.conversation.name = payload.data.name;
        if (payload.data.members != null)
            systemMessage.conversation.members = payload.data.members.others;

        return systemMessage;
    }

    protected WireClient getWireClient(UUID botId, Payload payload) throws IOException, CryptoException {
        return repo.getClient(botId);
    }

    protected boolean isValid(String auth) {
        return validator.validate(auth);
    }

    protected void handleUpdate(UUID id, Payload payload) {
        switch (payload.type) {
            case "team.member-join": {
                Logger.debug("%s: team: %s, user: %s", payload.type, payload.team, payload.data.user);
                handler.onNewTeamMember(id, payload.team, payload.data.user);
            }
            break;
            case "user.update": {
                Logger.debug("%s: id: %s", payload.type, payload.user.id);
                handler.onUserUpdate(id, payload.user.id);
            }
            break;
            default:
                Logger.debug("Unknown event: %s", payload.type);
                break;
        }
    }

    private Messages.GenericMessage decrypt(WireClient client, Payload payload)
            throws CryptoException, InvalidProtocolBufferException {
        String from = payload.from.toString();
        String sender = payload.data.sender;
        String cipher = payload.data.text;

        String encoded = client.decrypt(from, sender, cipher);
        byte[] decoded = Base64.getDecoder().decode(encoded);
        return Messages.GenericMessage.parseFrom(decoded);
    }
}
