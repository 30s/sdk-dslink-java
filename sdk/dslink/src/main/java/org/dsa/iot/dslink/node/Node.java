package org.dsa.iot.dslink.node;

import org.dsa.iot.dslink.link.Linkable;
import org.dsa.iot.dslink.node.NodeListener.ValueUpdate;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.serializer.SerializationManager;
import org.dsa.iot.dslink.util.StringUtils;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Contains information about a node and its data.
 *
 * @author Samuel Grenier
 */
public class Node {

    static final char[] BANNED_CHARS = new char[] {
            '%', '.', '/', '\\', '?', '*', ':', '|', '<', '>', '$', '@'
    };

    private final Object roConfigLock = new Object();
    private final Object configLock = new Object();

    private final Object attributeLock = new Object();
    private final Object interfaceLock = new Object();
    private final Object childrenLock = new Object();
    private final Object passwordLock = new Object();
    private final Object valueLock = new Object();
    private final Object actLock = new Object();

    private final WeakReference<Node> parent;
    private final Linkable link;
    private final String path;
    private final String name;

    private boolean serializable = true;
    private Map<String, Node> children;
    private NodeListener listener;
    private Writable writable;
    private Object metaData;

    private Map<String, Value> roConfigs;
    private Map<String, Value> configs;
    private Map<String, Value> attribs;
    private Boolean hasChildren;
    private boolean hidden;

    private ValueType valueType;
    private Value value;

    private String displayName;
    private String profile;
    private Set<String> interfaces;
    private Action action;
    private char[] pass;

    /**
     * Constructs a node object.
     *
     * @param name   Name of the node
     * @param parent Parent of this node
     * @param link Linkable class the node is handled on
     */
    public Node(String name, Node parent, Linkable link) {
        this.parent = new WeakReference<>(parent);
        this.listener = new NodeListener(this);
        this.link = link;
        name = StringUtils.encodeName(name);
        if (parent != null) {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("name");
            }
            this.name = name;
            this.path = parent.getPath() + "/" + name;
        } else {
            if (name != null) {
                this.path = "/" + name;
                this.name = name;
            } else {
                this.path = "";
                this.name = "";
            }
        }
    }

    /**
     * @return Parent of this node, can be null if the parent was garbage
     *         collected or there is no parent.
     */
    public Node getParent() {
        return parent.get();
    }

    /**
     * @return The link this node is attached to.
     */
    public Linkable getLink() {
        return link;
    }

    /**
     * @return Encoded name of the node.
     * @see StringUtils#decodeName(String)
     */
    public String getName() {
        return name;
    }

    /**
     * @return Formalized path of this node.
     */
    public String getPath() {
        return path;
    }

    /**
     * @param name Display name of the node to set
     */
    public void setDisplayName(String name) {
        displayName = name;
        markChanged();
        if (link != null) {
            SubscriptionManager man = link.getSubscriptionManager();
            if (name != null) {
                man.postMetaUpdate(this, "$name", new Value(displayName));
            } else {
                man.postMetaUpdate(this, "$name", null);
            }
        }
    }

    /**
     * @return Display name of the node
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Sets the profile of the node
     *
     * @param profile Profile to set
     */
    public void setProfile(String profile) {
        this.profile = profile;
        markChanged();
    }

    /**
     * @return The profile this node belongs to
     */
    public String getProfile() {
        return profile;
    }

    /**
     * The listener API provides functionality for listening to changes
     * that occur within a node.
     *
     * @return The node's listener.
     */
    public NodeListener getListener() {
        return listener;
    }

    /**
     * Used to set the listener to allow the node builder to override
     * the internal listener.
     *
     * @param listener Listener to set.
     */
    protected void setListener(NodeListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }
        this.listener = listener;
    }

    public void addInterface(String _interface) {
        synchronized (interfaceLock) {
            if (_interface == null) {
                throw new NullPointerException("_interface");
            } else if (interfaces == null) {
                interfaces = new HashSet<>();
            }
            interfaces.add(_interface);
            markChanged();
        }
    }

    @SuppressWarnings("unused")
    public void removeInterface(String _interface) {
        synchronized (interfaceLock) {
            if (_interface == null) {
                throw new NullPointerException("_interface");
            } else if (interfaces != null) {
                interfaces.remove(_interface);
                markChanged();
            }
        }
    }

    public void setInterfaces(String _interface) {
        synchronized (interfaceLock) {
            if (_interface == null) {
                throw new NullPointerException("_interface");
            } else if (interfaces == null) {
                interfaces = new HashSet<>();
            }
            String[] split = _interface.split("\\|");
            Collections.addAll(interfaces, split);
            markChanged();
        }
    }

    public Set<String> getInterfaces() {
        Set<String> i = this.interfaces;
        return i != null ? Collections.unmodifiableSet(i) : null;
    }

    public void setValue(Value value) {
        setValue(value, false);
    }

    /**
     * @param value Value to set.
     * @param externalSource Whether the value was set from an external source
     *                       like an action that got invoked.
     */
    public void setValue(Value value, boolean externalSource) {
        ValueType type = valueType;
        if (type == null) {
            String err = "Value type not set on node (" + getPath() + ")";
            throw new RuntimeException(err);
        }

        ValuePair pair;
        synchronized (valueLock) {
            pair = new ValuePair(this.value, value, externalSource);
        }
        if (listener.postValueUpdate(pair)) {
            return;
        }
        if (value != null) {
            value.setImmutable();
            if (type.compare(ValueType.ENUM)) {
                if (!value.getType().compare(ValueType.STRING)) {
                    String err = "[" + getPath() + "] ";
                    err += "Node has enum value type, value must be string";
                    throw new RuntimeException(err);
                } else if (type.getEnums() == null
                        || !type.getEnums().contains(value.getString())) {
                    String err = "[" + getPath() + "] ";
                    err += "New value does not contain a valid enum value";
                    throw new RuntimeException(err);
                }
            } else if (type.compare(ValueType.TIME)) {
                if (!value.getType().compare(ValueType.STRING)) {
                    String err = "[" + getPath() + "] ";
                    err += "Node has time value type, value must be string";
                    throw new RuntimeException(err);
                }
            } else if (!(type.compare(ValueType.DYNAMIC)
                    || type.compare(value.getType()))) {
                String err = "[" + getPath() + "] ";
                err += "Expected value type ";
                err += "'" + type.toJsonString() + "' ";
                err += "got '" + value.getType().toJsonString() + "'";
                throw new RuntimeException(err);
            }
        }
        synchronized (valueLock) {
            this.value = value;
            markChanged();
            if (link != null) {
                SubscriptionManager manager = link.getSubscriptionManager();
                if (manager != null) {
                    manager.postValueUpdate(this);
                }
            }
        }
    }

    /**
     * @return The value of the node.
     */
    public Value getValue() {
        return value;
    }

    public void setValueType(ValueType type) {
        this.valueType = type;
        markChanged();
        if (link != null) {
            SubscriptionManager man = link.getSubscriptionManager();
            if (type != null) {
                String t = type.toJsonString();
                man.postMetaUpdate(this, "$type", new Value(t));
            } else {
                man.postMetaUpdate(this, "$type", null);
            }
        }
    }

    public ValueType getValueType() {
        return valueType;
    }

    /**
     * @param writable Permission level required to write.
     */
    public void setWritable(Writable writable) {
        this.writable = writable;
        markChanged();
    }

    /**
     * @return The permission level needed to be writable.
     */
    public Writable getWritable() {
        return writable;
    }

    /**
     * @return Children of the node, can be null
     */
    public Map<String, Node> getChildren() {
        Map<String, Node> children = this.children;
        return children != null ? Collections.unmodifiableMap(children) : null;
    }

    /**
     * Clears the children in the node.
     */
    @SuppressWarnings("unused")
    public void clearChildren() {
        synchronized (childrenLock) {
            if (children != null) {
                Map<String, Node> children = getChildren();
                for (Node child : children.values()) {
                    removeChild(child);
                }
            }
            markChanged();
        }
    }

    /**
     * @param name Child name
     * @return Child, or null if non-existent
     */
    public Node getChild(String name) {
        Map<String, Node> children = this.children;
        if (children != null) {
            name = StringUtils.encodeName(name);
            return children.get(name);
        }
        return null;
    }

    /**
     * Creates a child. The profile in the child node will be
     * inherited from the parent.
     *
     * @param name Name of the child
     * @return builder
     */
    public NodeBuilder createChild(String name) {
        return createChild(name, profile);
    }

    /**
     * Creates a node builder to allow setting up the node data before
     * any list subscriptions can be notified.
     *
     * @param name Name of the child.
     * @param profile Profile to set on the child
     * @return builder
     * @see NodeBuilder#build
     */
    public NodeBuilder createChild(String name, String profile) {
        NodeBuilder b = new NodeBuilder(this, new Node(name, this, link));
        if (profile != null) {
            b.setProfile(profile);
        }
        return b;
    }

    /**
     * The child will be added if the node doesn't exist. If the child
     * already exists then it will be returned and no new node will be
     * created. This can be used as a special getter.
     *
     * @param node Child node to add.
     * @return The node
     */
    public Node addChild(Node node) {
        synchronized (childrenLock) {
            String name = node.getName();
            if (children == null) {
                children = new ConcurrentHashMap<>();
            } else if (children.containsKey(name)) {
                return children.get(name);
            }

            SubscriptionManager manager = null;
            if (link != null) {
                manager = link.getSubscriptionManager();
            }

            if (node.getProfile() == null) {
                node.setProfile(profile);
            }
            children.put(name, node);
            if (manager != null) {
                manager.postChildUpdate(node, false);
            }
            if (node.isSerializable()) {
                markChanged();
            }
            return node;
        }
    }

    /**
     * @param node Node to remove.
     * @return The node if it existed.
     */
    public Node removeChild(Node node) {
        if (node != null) {
            return removeChild(node.getName());
        } else {
            return null;
        }
    }

    /**
     * @param name Node to remove.
     * @return The node if it existed.
     */
    public Node removeChild(String name) {
        synchronized (childrenLock) {
            name = StringUtils.encodeName(name);
            Node child = children != null ? children.remove(name) : null;
            SubscriptionManager manager = null;
            if (link != null) {
                manager = link.getSubscriptionManager();
            }

            if (child != null) {
                child.getListener().kill();
                if (manager != null) {
                    manager.postChildUpdate(child, true);
                    manager.removeValueSub(child);
                    manager.removePathSub(child);
                }
                if (isSerializable()) {
                    markChanged();
                }
            }
            return child;
        }
    }

    /**
     * @param name Name of the child.
     * @return Whether this node has the child or not.
     */
    public boolean hasChild(String name) {
        Map<String, Node> children = this.children;
        if (children != null) {
            name = StringUtils.encodeName(name);
            return children.containsKey(name);
        }
        return false;
    }

    /**
     * @return The configurations in this node.
     */
    public Map<String, Value> getConfigurations() {
        Map<String, Value> c = this.configs;
        return c != null ? Collections.unmodifiableMap(c) : null;
    }

    /**
     * @param name Configuration name to get
     * @return Value of the configuration, if it exists
     */
    public Value getConfig(String name) {
        Map<String, Value> c = configs;
        if (c != null) {
            name = StringUtils.encodeName(name);
            return c.get(name);
        }
        return null;
    }

    /**
     * @param name Configuration name to remove
     * @return Configuration value, or null if it didn't exist
     */
    public Value removeConfig(String name) {
        synchronized (configLock) {
            name = StringUtils.encodeName(name);
            Value ret = configs != null ? configs.remove(name) : null;
            if (ret != null) {
                ValueUpdate update = new ValueUpdate(name, ret, true);
                listener.postConfigUpdate(update);
            }

            SubscriptionManager man = link.getSubscriptionManager();
            if (man != null) {
                man.postMetaUpdate(this, "$" + name, null);
            }

            markChanged();
            return ret;
        }
    }

    /**
     * The name will be checked for validity. Certain names that are set
     * through other APIs cannot be set here, otherwise it will throw an
     * exception.
     *
     * @param name  Name of the configuration
     * @param value Value to set
     * @return The previous configuration value, if any
     * @see Action
     */
    public Value setConfig(String name, Value value) {
        synchronized (configLock) {
            name = checkAndEncodeName(name);
            if (value == null) {
                throw new NullPointerException("value");
            } else if (configs == null) {
                configs = new ConcurrentHashMap<>();
            }
            switch (name) {
                case "params":
                case "columns":
                case "name":
                case "is":
                case "invokable":
                case "interface":
                case "permission":
                case "result":
                case "type":
                case "writable":
                case "hidden":
                    String err = "Config `" + name + "` has special methods"
                            + " for setting these properties";
                    throw new IllegalArgumentException(err);
            }
            value.setImmutable();
            ValueUpdate update = new ValueUpdate(name, value, false);
            NodeListener listener = this.listener;
            if (listener != null) {
                listener.postConfigUpdate(update);
            }

            SubscriptionManager man = link.getSubscriptionManager();
            if (man != null) {
                man.postMetaUpdate(this, "$" + name, value);
            }

            markChanged();
            return configs.put(name, value);
        }
    }

    /**
     * @return The read-only configurations in this node.
     */
    public Map<String, Value> getRoConfigurations() {
        Map<String, Value> c = this.roConfigs;
        return c != null ? Collections.unmodifiableMap(c) : null;
    }

    /**
     * Removes a read-only configuration.
     *
     * @param name Name of the configuration.
     * @return Previous value of the configuration, if any.
     */
    public Value removeRoConfig(String name) {
        synchronized (roConfigLock) {
            name = StringUtils.encodeName(name);
            Value tmp = roConfigs != null ? roConfigs.remove(name) : null;
            if (tmp != null) {
                SubscriptionManager man = link.getSubscriptionManager();
                if (man != null) {
                    man.postMetaUpdate(this, "$$" + name, null);
                }
            }
            markChanged();
            return tmp;
        }
    }

    /**
     * Retrieves a read-only configuration.
     *
     * @param name Name of the configuration.
     * @return The value of the configuration name, if any.
     */
    @SuppressWarnings("unused")
    public Value getRoConfig(String name) {
        Map<String, Value> c = roConfigs;
        if (c != null) {
            name = StringUtils.encodeName(name);
            return c.get(name);
        }
        return null;
    }

    /**
     * Sets a read-only configuration.
     *
     * @param name Name of the configuration.
     * @param value Value to set.
     * @return The previous value, if any.
     */
    public Value setRoConfig(String name, Value value) {
        synchronized (roConfigLock) {
            name = checkAndEncodeName(name);
            if (value == null) {
                throw new NullPointerException("value");
            } else if (roConfigs == null) {
                roConfigs = new ConcurrentHashMap<>();
            }

            switch (name) {
                case "password":
                    String err = "Config `" + name + "` has special methods"
                            + " for setting these properties";
                    throw new IllegalArgumentException(err);
            }

            SubscriptionManager man = link.getSubscriptionManager();
            if (man != null) {
                man.postMetaUpdate(this, "$$" + name, value);
            }

            markChanged();
            return roConfigs.put(name, value);
        }
    }

    /**
     * @return The attributes in this node.
     */
    public Map<String, Value> getAttributes() {
        Map<String, Value> a = attribs;
        return a != null ? Collections.unmodifiableMap(a) : null;
    }

    /**
     * @param name Attribute name to get
     * @return Value of the attribute, if it exists
     */
    public Value getAttribute(String name) {
        Map<String, Value> a = attribs;
        if (a != null) {
            name = StringUtils.encodeName(name);
            return a.get(name);
        }
        return null;
    }

    /**
     * @param name Attribute name to remove.
     * @return Attribute value or null if it didn't exist
     */
    public Value removeAttribute(String name) {
        synchronized (attributeLock) {
            name = StringUtils.encodeName(name);
            Value ret = attribs != null ? attribs.remove(name) : null;
            if (ret != null) {
                ValueUpdate update = new ValueUpdate(name, ret, true);
                listener.postAttributeUpdate(update);

                SubscriptionManager man = link.getSubscriptionManager();
                if (man != null) {
                    man.postMetaUpdate(this, "@" + name, null);
                }
            }

            markChanged();
            return ret;
        }
    }

    /**
     * @param name  Name of the attribute
     * @param value Value to set
     * @return The previous attribute value, if any
     */
    public Value setAttribute(String name, Value value) {
        synchronized (attributeLock) {
            name = checkAndEncodeName(name);
            if (value == null) {
                throw new NullPointerException("value");
            } else if (attribs == null) {
                attribs = new ConcurrentHashMap<>();
            }
            value.setImmutable();
            ValueUpdate update = new ValueUpdate(name, value, false);
            listener.postAttributeUpdate(update);

            SubscriptionManager man = link.getSubscriptionManager();
            if (man != null) {
                man.postMetaUpdate(this, "@" + name, value);
            }

            markChanged();
            return attribs.put(name, value);
        }
    }

    /**
     * @return Action this node can invoke
     */
    public Action getAction() {
        return action;
    }

    /**
     * Sets the action of the node.
     *
     * @param action Action to set. Use {@code null} to remove an action.
     */
    public void setAction(Action action) {
        this.action = action;
        markChanged();
        if (link != null) {
            SubscriptionManager man = link.getSubscriptionManager();
            if (man != null) {
                synchronized (actLock) {
                    if (!(action == null || action.isHidden())) {
                        Value params = new Value(action.getParams());
                        Value cols = new Value(action.getColumns());
                        man.postMetaUpdate(this, "$params", params);
                        man.postMetaUpdate(this, "$columns", cols);
                        action.setSubscriptionManager(this, man);
                    } else {
                        man.postMetaUpdate(this, "$params", null);
                        man.postMetaUpdate(this, "$columns", null);
                    }
                }
            }
        }
    }

    /**
     * Gets the password the node is configured to use. This is necessary
     * for authentication to servers.
     *
     * @return Password the node is configured to use.
     */
    public char[] getPassword() {
        synchronized (passwordLock) {
            return pass != null ? pass.clone() : null;
        }
    }

    /**
     * If this node accesses servers and requires authentication, the password
     * must be set here. This will censor the password from being retrieved
     * through the responder.
     *
     * @param password Password to set.
     */
    public void setPassword(char[] password) {
        synchronized (passwordLock) {
            this.pass = password != null ? password.clone() : null;
            markChanged();
        }
    }

    /**
     * Forcibly sets whether the node has children or not. This does <b>NOT</b>
     * affect whether the node has children internally.
     *
     * @param hasChildren Whether the node hsa children or not.
     */
    public void setHasChildren(Boolean hasChildren) {
        this.hasChildren = hasChildren;
        markChanged();
    }

    /**
     * Checks whether the node is forced to have children or not. This does
     * <b>NOT</b> check if the node has children internally. To check whether
     * the node has children internally, use {@link #getChildren()}.
     *
     * @return Whether the node has children or not.
     */
    public Boolean getHasChildren() {
        return hasChildren;
    }

    /**
     * @param hidden Whether the node is marked as hidden.
     */
    public void setHidden(boolean hidden) {
        this.hidden = hidden;
        markChanged();
    }

    /**
     * When a node is marked as hidden the UI should not display
     * the node.
     *
     * @return Whether the node is marked as hidden.
     */
    public boolean isHidden() {
        return hidden;
    }

    /**
     * Creates a fake node builder that wraps its methods around
     * this node. This allows fitting a {@link Node} into a {@link NodeBuilder}
     * when necessary.
     *
     * @return A fake node builder.
     */
    public NodeBuilder createFakeBuilder() {
        return new NodeBuilder(getParent(), this) {
            @Override
            public Node build() {
                return Node.this;
            }
        };
    }

    /**
     * If this node is not serializable, none of the children will be either
     * by default.
     *
     * @return Whether this node should be serialized or not
     */
    public boolean isSerializable() {
        return serializable;
    }

    /**
     * Sets whether this node and its children should be serialized.
     *
     * @param serializable Whether this node can be serialized.
     */
    public void setSerializable(boolean serializable) {
        this.serializable = serializable;
        markChanged();
    }

    /**
     * Sets the meta data of the node. Used for attaching extra information
     * to a node. This meta data is not serialized. The sole purpose of meta
     * data is to attach a custom instance that operates on this node.
     *
     * @param object Meta data object.
     */
    public void setMetaData(Object object) {
        if (object instanceof MetaData) {
            ((MetaData) object).setNode(this);
        }
        this.metaData = object;
    }

    /**
     * @param <T> Meta data to cast to.
     * @return The attached meta data of this node.
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetaData() {
        return (T) metaData;
    }

    private void markChanged() {
        if (!isSerializable()) {
            return;
        }
        Linkable link = getLink();
        if (link != null) {
            SerializationManager sm = link.getSerialManager();
            if (sm != null) {
                sm.markChanged();
            }
        }
    }

    /**
     * Checks the string and then returns it. An exception is thrown if the
     * name is invalid in any way.
     *
     * @param name Name to check
     * @return Name
     */
    public static String checkAndEncodeName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name");
        }
        return StringUtils.encodeName(name);
    }

    /**
     * @return The banned characters not allowed to be in names.
     */
    public static char[] getBannedCharacters() {
        return BANNED_CHARS.clone();
    }
}
