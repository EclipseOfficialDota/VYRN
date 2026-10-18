package xyz.eclipseofficial.vyrn.domain;

import java.util.List;

public final class Models {
    private Models() {}
    public static final class User {
        public final String id, name, firstName, lastName, about, avatarId;
        public User(String id, String name) { this(id,name,name,"",""); }
        public User(String id,String name,String firstName,String lastName,String about){this(id,name,firstName,lastName,about,null);}
        public User(String id,String name,String firstName,String lastName,String about,String avatarId){this.id=id;this.name=name;this.firstName=firstName;this.lastName=lastName;this.about=about;this.avatarId=avatarId;}
    }
    public static final class Avatar {
        public final String id,contentType;public final int width,height,size,date;
        public Avatar(String id,String contentType,int width,int height,int size,int date){this.id=id;this.contentType=contentType;this.width=width;this.height=height;this.size=size;this.date=date;}
    }
    public static final class Profile {
        public final User user;public final boolean blocked;public final int commonGroups;
        public Profile(User user,boolean blocked,int commonGroups){this.user=user;this.blocked=blocked;this.commonGroups=commonGroups;}
    }
    public static final class BlockedUser {
        public final User user;public final int date;
        public BlockedUser(User user,int date){this.user=user;this.date=date;}
    }
    public static final class BlockedPage {
        public final List<BlockedUser> users;public final int count;
        public BlockedPage(List<BlockedUser> users,int count){this.users=users;this.count=count;}
    }
    public static final class Conversation {
        public final String id, kind, title, role;
        public final int members,disappearingSeconds,unreadCount;
        public Conversation(String id,String kind,String title,String role,int members,int disappearingSeconds) { this(id,kind,title,role,members,disappearingSeconds,0); }
        public Conversation(String id,String kind,String title,String role,int members,int disappearingSeconds,int unreadCount) { this.id=id;this.kind=kind;this.title=title;this.role=role;this.members=members;this.disappearingSeconds=disappearingSeconds;this.unreadCount=unreadCount; }
    }
    public static final class Message {
        public final Attachment attachment;
        /** Null means unavailable metadata, an empty list means no reactions. */
        public final List<Reaction> reactions;
        public final String id, sender, text, conversation;
        public final int date, ordinal, revision, editedDate;
        public final boolean encrypted, deleted;
        public Message(String id,String sender,String text,int date,int ordinal,boolean encrypted,boolean deleted){this(id,sender,text,date,ordinal,encrypted,deleted,null,0,0);}
        public Message(String id,String sender,String text,int date,int ordinal,boolean encrypted,boolean deleted,String conversation,int revision,int editedDate){this(id,sender,text,date,ordinal,encrypted,deleted,conversation,revision,editedDate,null);}
        public Message(String id,String sender,String text,int date,int ordinal,boolean encrypted,boolean deleted,String conversation,int revision,int editedDate,Attachment attachment){this(id,sender,text,date,ordinal,encrypted,deleted,conversation,revision,editedDate,attachment,null);}
        public Message(String id,String sender,String text,int date,int ordinal,boolean encrypted,boolean deleted,String conversation,int revision,int editedDate,Attachment attachment,List<Reaction> reactions){this.id=id;this.sender=sender;this.text=text;this.date=date;this.ordinal=ordinal;this.encrypted=encrypted;this.deleted=deleted;this.conversation=conversation;this.revision=revision;this.editedDate=editedDate;this.attachment=attachment;this.reactions=reactions==null?null:java.util.Collections.unmodifiableList(new java.util.ArrayList<>(reactions));}
    }
    public static final class Reaction {
        public final String emoji;public final int count;public final boolean mine;
        public Reaction(String emoji,int count,boolean mine){this.emoji=emoji;this.count=count;this.mine=mine;}
    }
    public static final class SearchPage {
        public final List<Message> messages;public final int total;
        public SearchPage(List<Message> messages,int total){this.messages=messages;this.total=total;}
    }
    public static final class Attachment {
        public final String id,conversation,kind,filename,contentType,sha256;public final long size,durationMs;public final boolean encrypted;
        public final int width,height;
        public Attachment(String id,String conversation,String kind,String filename,String contentType,String sha256,long size,boolean encrypted){this(id,conversation,kind,filename,contentType,sha256,size,encrypted,0,0);}
        public Attachment(String id,String conversation,String kind,String filename,String contentType,String sha256,long size,boolean encrypted,int width,int height){this(id,conversation,kind,filename,contentType,sha256,size,encrypted,width,height,0);}
        public Attachment(String id,String conversation,String kind,String filename,String contentType,String sha256,long size,boolean encrypted,int width,int height,long durationMs){this.id=id;this.conversation=conversation;this.kind=kind;this.filename=filename;this.contentType=contentType;this.sha256=sha256;this.size=size;this.encrypted=encrypted;this.width=width;this.height=height;this.durationMs=durationMs;}
    }
    public static final class Member {
        public final User user;public final String role;public final int joinedDate;
        public Member(User user,String role,int joinedDate){this.user=user;this.role=role;this.joinedDate=joinedDate;}
    }
    public static final class ConversationDetails {
        public final Conversation conversation;public final List<Member> members;public final int createdDate;
        public ConversationDetails(Conversation conversation,List<Member> members,int createdDate){this.conversation=conversation;this.members=members;this.createdDate=createdDate;}
    }
    public interface Repository {
        User me() throws Exception;
        List<Conversation> conversations() throws Exception;
        List<User> members(String conversation) throws Exception;
        List<Message> messages(String conversation,String before,int limit) throws Exception;
        String send(String conversation,String clientId,String text) throws Exception;
    }
}
