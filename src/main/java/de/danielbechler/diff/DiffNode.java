/*
 * Copyright 2012 Daniel Bechler
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.danielbechler.diff;

import de.danielbechler.diff.bean.BeanPropertyElementSelector;
import de.danielbechler.diff.visitor.PropertyVisitor;
import de.danielbechler.util.Assert;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.Collections.unmodifiableSet;

/**
 * Represents a part of an object. It could be the object itself, one of its properties, an item in a
 * collection or a map entry. A node may have one parent node and any number of children. It also provides
 * methods to read and write the property represented by this node on any object of the same type as the
 * original object. Last but not least, a node knows how the associated property has changed compared to the
 * base object.
 *
 * @author Daniel Bechler
 */
public class DiffNode
{
	public static final DiffNode ROOT = null;

	private final Accessor accessor;
	private final Map<ElementSelector, DiffNode> children = new LinkedHashMap<ElementSelector, DiffNode>(10);

	private State state = State.UNTOUCHED;
	private DiffNode parentNode;
	private NodePath circleStartPath;
	private DiffNode circleStartNode;
	private Class<?> valueType;

	public DiffNode(final DiffNode parentNode, final Accessor accessor, final Class<?> valueType)
	{
		Assert.notNull(accessor, "accessor");
		this.accessor = accessor;
		this.valueType = valueType;
		setParentNode(parentNode);
	}

	public DiffNode(final Accessor accessor, final Class<?> valueType)
	{
		this(ROOT, accessor, valueType);
	}

	public DiffNode(final Class<?> valueType)
	{
		this(ROOT, RootAccessor.getInstance(), valueType);
	}

	public DiffNode()
	{
		this(ROOT, RootAccessor.getInstance(), null);
	}

	/**
	 * @return The state of this node.
	 */
	public State getState()
	{
		return this.state;
	}

	/**
	 * @param state The state of this node.
	 */
	public void setState(final State state)
	{
		Assert.notNull(state, "state");
		this.state = state;
	}

	public boolean matches(final NodePath path)
	{
		return path.matches(getPath());
	}

	public boolean hasChanges()
	{
		if (isAdded() || isChanged() || isRemoved())
		{
			return true;
		}
		final AtomicBoolean result = new AtomicBoolean(false);
		visitChildren(new Visitor()
		{
			public void accept(final DiffNode node, final Visit visit)
			{
				if (node.hasChanges())
				{
					result.set(true);
					visit.stop();
				}
			}
		});
		return result.get();
	}

	/**
	 * Convenience method for <code>{@link #getState()} == {@link DiffNode.State#ADDED}</code>
	 */
	public final boolean isAdded()
	{
		return state == State.ADDED;
	}

	/**
	 * Convenience method for <code>{@link #getState()} == {@link DiffNode.State#CHANGED}</code>
	 */
	public final boolean isChanged()
	{
		return state == State.CHANGED;
	}

	/**
	 * Convenience method for <code>{@link #getState()} == {@link DiffNode.State#REMOVED}</code>
	 */
	public final boolean isRemoved()
	{
		return state == State.REMOVED;
	}

	/**
	 * Convenience method for <code>{@link #getState()} == {@link DiffNode.State#UNTOUCHED}</code>
	 */
	public final boolean isUntouched()
	{
		return state == State.UNTOUCHED;
	}

	/**
	 * Convenience method for <code>{@link #getState()} == {@link DiffNode.State#CIRCULAR}</code>
	 */
	public boolean isCircular()
	{
		return state == State.CIRCULAR;
	}

	/**
	 * @return The absolute property path from the object root up to this node.
	 */
	public final NodePath getPath()
	{
		if (parentNode != null)
		{
			return NodePath.startBuildingFrom(parentNode.getPath())
					.element(accessor.getElementSelector())
					.build();
		}
		else if (accessor instanceof RootAccessor)
		{
			return NodePath.withRoot();
		}
		else
		{
			return NodePath.startBuilding().element(accessor.getElementSelector()).build();
		}
	}

	public ElementSelector getElementSelector()
	{
		return accessor.getElementSelector();
	}

	/**
	 * @return Returns the type of the property represented by this node, or null if unavailable.
	 */
	public Class<?> getValueType()
	{
		if (valueType != null)
		{
			return valueType;
		}
		if (accessor instanceof TypeAwareAccessor)
		{
			return ((TypeAwareAccessor) accessor).getType();
		}
		return null;
	}

	/**
	 * Allows for explicit type definition. However, if the accessor is TypeAware, {@link #getValueType()} will
	 * always return the type returned by the accessor.
	 *
	 * @param aClass The type of the value represented by this node.
	 */
	public void setType(final Class<?> aClass)
	{
		this.valueType = aClass;
	}

	/**
	 * @return <code>true</code> if this node has children.
	 */
	public boolean hasChildren()
	{
		return !children.isEmpty();
	}

	public int childCount()
	{
		return children.size();
	}

	/**
	 * Retrieve a child with the given property name relative to this node.
	 *
	 * @param propertyName The name of the property represented by the child node.
	 * @return The requested child node or <code>null</code>.
	 */
	public DiffNode getChild(final String propertyName)
	{
		return getChild(new BeanPropertyElementSelector(propertyName));
	}

	/**
	 * Retrieve a child that matches the given absolute path, starting from the current node.
	 *
	 * @param path The path from the object root to the requested child node.
	 * @return The requested child node or <code>null</code>.
	 */
	public DiffNode getChild(final NodePath path)
	{
		final PropertyVisitor visitor = new PropertyVisitor(path);
		visitChildren(visitor);
		return visitor.getNode();
	}

	/**
	 * Retrieve a child that matches the given path element relative to this node.
	 *
	 * @param pathElementSelector The path element of the child node to get.
	 * @return The requested child node or <code>null</code>.
	 */
	public DiffNode getChild(final ElementSelector pathElementSelector)
	{
		return children.get(pathElementSelector);
	}

	/**
	 * Adds a child to this node and sets this node as its parent node.
	 *
	 * @param node The node to add.
	 */
	public boolean addChild(final DiffNode node)
	{
		if (node.isRootNode())
		{
			throw new IllegalArgumentException("Detected attempt to add root node as child. " +
					"This is not allowed and must be a mistake.");
		}
		else if (node == this)
		{
			throw new IllegalArgumentException("Detected attempt to add a node to itself. " +
					"This would cause inifite loops and must never happen.");
		}
		else if (node.getParentNode() != null && node.getParentNode() != this)
		{
			throw new IllegalArgumentException("Detected attempt to add child node that is already the " +
					"child of another node. Adding nodes multiple times is not allowed, since it could " +
					"cause infinite loops.");
		}
		final ElementSelector pathElementSelector = node.getElementSelector();
		if (node.getParentNode() == null)
		{
			node.setParentNode(this);
			children.put(pathElementSelector, node);
		}
		else if (node.getParentNode() == this)
		{
			children.put(pathElementSelector, node);
		}
		else
		{
			throw new IllegalStateException("Detected attempt to replace the parent node of node at path '" + getPath() + "'");
		}
		if (state == State.UNTOUCHED && node.hasChanges())
		{
			state = State.CHANGED;
		}
		return true;
	}

	/**
	 * Visit this and all child nodes.
	 *
	 * @param visitor The visitor to use.
	 */
	public final void visit(final Visitor visitor)
	{
		final Visit visit = new Visit();
		try
		{
			visit(visitor, visit);
		}
		catch (final StopVisitationException ignored)
		{
		}
	}

	protected final void visit(final Visitor visitor, final Visit visit)
	{
		try
		{
			visitor.accept(this, visit);
		}
		catch (final StopVisitationException e)
		{
			visit.stop();
		}
		if (visit.isAllowedToGoDeeper() && hasChildren())
		{
			visitChildren(visitor);
		}
		if (visit.isStopped())
		{
			throw new StopVisitationException();
		}
	}

	/**
	 * Visit all child nodes but not this one.
	 *
	 * @param visitor The visitor to use.
	 */
	public final void visitChildren(final Visitor visitor)
	{
		for (final DiffNode child : children.values())
		{
			try
			{
				child.visit(visitor);
			}
			catch (final StopVisitationException e)
			{
				return;
			}
		}
	}

	/**
	 * If this node represents a bean property this method returns all annotations of its getter.
	 *
	 * @return A set of annotations of this nodes property getter or an empty set.
	 */
	public Set<Annotation> getPropertyAnnotations()
	{
		if (accessor instanceof PropertyAwareAccessor)
		{
			return unmodifiableSet(((PropertyAwareAccessor) accessor).getReadMethodAnnotations());
		}
		return unmodifiableSet(Collections.<Annotation>emptySet());
	}

	public <T extends Annotation> T getPropertyAnnotation(final Class<T> annotationClass)
	{
		if (accessor instanceof PropertyAwareAccessor)
		{
			return ((PropertyAwareAccessor) accessor).getReadMethodAnnotation(annotationClass);
		}
		return null;
	}

	/**
	 * If this node represents a bean property, this method will simply return its name. Otherwise it will return the
	 * property name of its closest bean property representing ancestor. This way intermediate nodes like those
	 * representing collection, map or array items will be semantically tied to their container objects.
	 * <p/>
	 * That is especially useful for inclusion and exclusion rules. For example, when a List is explicitly included by
	 * property name, it would be weird if the inclusion didn't also apply to its items.
	 */
	public String getPropertyName()
	{
		if (isPropertyAware())
		{
			return ((PropertyAwareAccessor) accessor).getPropertyName();
		}
		else if (parentNode != null)
		{
			return parentNode.getPropertyName();
		}
		return null;
	}

	/**
	 * Returns <code>true</code> when this node represents a bean property and can therefore be queried for property
	 * specific information like annotations or property types. But there will also be nodes that represent collection
	 * items, map entries, etc. In those cases this method will return <code>false</code>.
	 */
	public final boolean isPropertyAware()
	{
		return accessor instanceof PropertyAwareAccessor;
	}

	public final boolean isRootNode()
	{
		return accessor instanceof RootAccessor;
	}

	/**
	 * Convenience method for <code>{@link #getState()} == {@link DiffNode.State#IGNORED}</code>
	 */
	public final boolean isIgnored()
	{
		return state == State.IGNORED;
	}

	public ComparisonStrategy getComparisonStrategy()
	{
		if (accessor instanceof ComparisonStrategyAwareAccessor)
		{
			return ((ComparisonStrategyAwareAccessor) accessor).getComparisonStrategy();
		}
		return null;
	}

	public boolean isExcluded()
	{
		if (accessor instanceof ExclusionAwareAccessor)
		{
			return ((ExclusionAwareAccessor) accessor).isExcluded();
		}
		return false;
	}

	public final Set<String> getCategories()
	{
		final Set<String> categories = new TreeSet<String>();
		if (parentNode != null)
		{
			categories.addAll(parentNode.getCategories());
		}
		if (accessor instanceof CategoryAwareAccessor)
		{
			final CategoryAwareAccessor categoryAwareAccessor = (CategoryAwareAccessor) accessor;
			final Set<String> categoriesFromAccessor = categoryAwareAccessor.getCategories();
			if (categoriesFromAccessor != null)
			{
				categories.addAll(categoriesFromAccessor);
			}
		}
		return categories;
	}

	/**
	 * @return The parent node, if any.
	 */
	public DiffNode getParentNode()
	{
		return parentNode;
	}

	/**
	 * Sets the parent node.
	 *
	 * @param parentNode The parent of this node. May be null, if this is a root node.
	 */
	protected final void setParentNode(final DiffNode parentNode)
	{
		if (this.parentNode != null && this.parentNode != parentNode)
		{
			throw new IllegalStateException("The parent of a node cannot be changed, once it's set.");
		}
		this.parentNode = parentNode;
	}

	public Object get(final Object target)
	{
		return accessor.get(target);
	}

	public void set(final Object target, final Object value)
	{
		accessor.set(target, value);
	}

	public void unset(final Object target)
	{
		accessor.unset(target);
	}

	public Object canonicalGet(Object target)
	{
		if (parentNode != null)
		{
			target = parentNode.canonicalGet(target);
		}
		return accessor.get(target);
	}

	public void canonicalSet(Object target, final Object value)
	{
		if (parentNode != null)
		{
			target = parentNode.canonicalGet(target);
		}
		accessor.set(target, value);
	}

	public void canonicalUnset(Object target)
	{
		if (parentNode != null)
		{
			target = parentNode.canonicalGet(target);
		}
		accessor.unset(target);
	}

	@Override
	public String toString()
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(getClass().getSimpleName());
		sb.append("(");
		sb.append("state=");
		sb.append(getState().toString());
		if (getValueType() != null)
		{
			sb.append(", type=").append(getValueType().getCanonicalName());
		}
		if (childCount() == 1)
		{
			sb.append(", ").append(childCount()).append(" child");
		}
		else if (childCount() > 1)
		{
			sb.append(", ").append(childCount()).append(" children");
		}
		else
		{
			sb.append(", no children");
		}
		if (!getCategories().isEmpty())
		{
			sb.append(", categorized as ").append(getCategories());
		}
		sb.append(", accessed via ").append(accessor);
		sb.append(')');
		return sb.toString();
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (o == null || getClass() != o.getClass())
		{
			return false;
		}

		final DiffNode that = (DiffNode) o;

		if (!accessor.equals(that.accessor))
		{
			return false;
		}

		return true;
	}

	@Override
	public int hashCode()
	{
		return accessor.hashCode();
	}

	/**
	 * @return Returns the path to the first node in the hierarchy that represents the same object instance as
	 * this one. (Only if {@link #isCircular()} returns <code>true</code>.
	 */
	public NodePath getCircleStartPath()
	{
		return circleStartPath;
	}

	public void setCircleStartPath(final NodePath circularStartPath)
	{
		this.circleStartPath = circularStartPath;
	}

	public DiffNode getCircleStartNode()
	{
		return circleStartNode;
	}

	public void setCircleStartNode(final DiffNode circleStartNode)
	{
		this.circleStartNode = circleStartNode;
	}

	/**
	 * The state of a {@link DiffNode} representing the difference between two objects.
	 */
	public enum State
	{
		/**
		 * The value has been added to the working object.
		 */
		ADDED,

		/**
		 * The value has been changed compared to the base object.
		 */
		CHANGED,

		/**
		 * The value has been removed from the working object.
		 */
		REMOVED,

		/**
		 * The value is identical between working and base
		 */
		UNTOUCHED,

		/**
		 * Special state to mark circular references
		 */
		CIRCULAR,

		/**
		 * The value has not been looked at and has been ignored.
		 */
		IGNORED
	}

	/**
	 * Visitor to traverse a node graph.
	 */
	public static interface Visitor
	{
		void accept(DiffNode node, Visit visit);
	}
}
