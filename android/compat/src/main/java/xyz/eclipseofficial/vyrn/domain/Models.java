package xyz.eclipseofficial.vyrn.domain;

import java.util.List;

public final class Models {
    private Models() {}
    public static final class User {
        public final String id, name;
        public User(String id, String name) { this.id=id; this.name=name; }
    }
    public static final class Conversation {
        public final String id, kind, title, role;
        public final int members;
        public Conversation(String id,String kind,String title,String role,int members) { this.id=id;this.kind=kind;this.title=title;this.role=role;this.members=members; }
    }
    public static final class Message {
        public final String id, sender, text;
        public final int date;
        public final boolean encrypted, deleted;
        public Message(String id,String sender,String text,int date,boolean encrypted,boolean deleted){this.id=id;this.sender=sender;this.text=text;this.date=date;this.encrypted=encrypted;this.deleted=deleted;}
    }
    public interface Repository {
        User me() throws Exception;
        List<Conversation> conversations() throws Exception;
        List<User> members(String conversation) throws Exception;
        List<Message> messages(String conversation,String before,int limit) throws Exception;
        String send(String conversation,String clientId,String text) throws Exception;
    }
}
