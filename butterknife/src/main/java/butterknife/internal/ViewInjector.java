package butterknife.internal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static butterknife.internal.ButterKnifeProcessor.VIEW_TYPE;

final class ViewInjector {
  private final Map<Integer, ViewInjection> viewIdMap = new LinkedHashMap<Integer, ViewInjection>();
  private final Map<CollectionBinding, int[]> collectionBindings =
      new LinkedHashMap<CollectionBinding, int[]>();
  private final String classPackage;
  private final String className;
  private final String targetClass;
  private String parentInjector;

  ViewInjector(String classPackage, String className, String targetClass) {
    this.classPackage = classPackage;
    this.className = className;
    this.targetClass = targetClass;
  }

  void addField(int id, String name, String type, boolean required) {
    getOrCreateViewBinding(id).addFieldBinding(new FieldBinding(name, type, required));
  }

  boolean addMethod(int id, ListenerClass listener, String name, List<Parameter> parameters,
      boolean required) {
    ViewInjection viewInjection = getOrCreateViewBinding(id);
    if (viewInjection.hasMethodBinding(listener)) {
      return false;
    }
    viewInjection.addMethodBinding(listener, new MethodBinding(name, parameters, required));
    return true;
  }

  void addCollection(int[] ids, String name, String type, CollectionBinding.Kind kind) {
    CollectionBinding binding = new CollectionBinding(name, type, kind);
    collectionBindings.put(binding, ids);
  }

  void setParentInjector(String parentInjector) {
    this.parentInjector = parentInjector;
  }

  private ViewInjection getOrCreateViewBinding(int id) {
    ViewInjection viewId = viewIdMap.get(id);
    if (viewId == null) {
      viewId = new ViewInjection(id);
      viewIdMap.put(id, viewId);
    }
    return viewId;
  }

  String getFqcn() {
    return classPackage + "." + className;
  }

  String brewJava() {
    StringBuilder builder = new StringBuilder();
    builder.append("// Generated code from Butter Knife. Do not modify!\n");
    builder.append("package ").append(classPackage).append(";\n\n");
    builder.append("import android.view.View;\n");
    builder.append("import butterknife.ButterKnife.Finder;\n\n");
    builder.append("public class ").append(className).append(" {\n");
    emitInject(builder);
    builder.append('\n');
    emitReset(builder);
    builder.append("}\n");
    return builder.toString();
  }

  private void emitInject(StringBuilder builder) {
    builder.append("  public static void inject(Finder finder, final ")
        .append(targetClass)
        .append(" target, Object source) {\n");

    // Emit a call to the superclass injector, if any.
    if (parentInjector != null) {
      builder.append("    ")
          .append(parentInjector)
          .append(".inject(finder, target, source);\n\n");
    }

    // Local variable in which all views will be temporarily stored.
    builder.append("    View view;\n");

    // Loop over each view injection and emit it.
    for (ViewInjection injection : viewIdMap.values()) {
      emitViewInjection(builder, injection);
    }

    // Loop over each collection binding and emit it.
    for (Map.Entry<CollectionBinding, int[]> entry : collectionBindings.entrySet()) {
      emitCollectionBinding(builder, entry.getKey(), entry.getValue());
    }

    builder.append("  }\n");
  }

  private void emitCollectionBinding(StringBuilder builder, CollectionBinding binding, int[] ids) {
    builder.append("    target.").append(binding.getName()).append(" = ");

    switch (binding.getKind()) {
      case ARRAY:
        builder.append("Finder.arrayOf(");
        break;
      case LIST:
        builder.append("Finder.listOf(");
        break;
      default:
        throw new IllegalStateException("Unknown kind: " + binding.getKind());
    }

    for (int i = 0; i < ids.length; i++) {
      if (i > 0) {
        builder.append(',');
      }
      builder.append("\n        ");
      emitCastIfNeeded(builder, binding.getType());
      builder.append("finder.findRequiredView(source, ")
          .append(ids[i])
          .append(", \"")
          .append(binding.getName())
          .append("\")");
    }

    builder.append("\n    );");
  }

  private void emitViewInjection(StringBuilder builder, ViewInjection injection) {
    builder.append("    view = ");

    List<Binding> requiredBindings = injection.getRequiredBindings();
    if (requiredBindings.isEmpty()) {
      builder.append("finder.findOptionalView(source, ")
          .append(injection.getId())
          .append(");\n");
    } else {
      builder.append("finder.findRequiredView(source, ")
          .append(injection.getId())
          .append(", \"");
      emitHumanDescription(builder, requiredBindings);
      builder.append("\");\n");
    }

    emitFieldBindings(builder, injection);
    emitMethodBindings(builder, injection);
  }

  private void emitFieldBindings(StringBuilder builder, ViewInjection injection) {
    Collection<FieldBinding> fieldBindings = injection.getFieldBindings();
    if (fieldBindings.isEmpty()) {
      return;
    }

    for (FieldBinding fieldBinding : fieldBindings) {
      builder.append("    target.")
          .append(fieldBinding.getName())
          .append(" = ");
      emitCastIfNeeded(builder, fieldBinding.getType());
      builder.append("view;\n");
    }
  }

  private void emitMethodBindings(StringBuilder builder, ViewInjection injection) {
    Map<ListenerClass, MethodBinding> methodBindings = injection.getMethodBindings();
    if (methodBindings.isEmpty()) {
      return;
    }

    String extraIndent = "";

    // We only need to emit the null check if there are zero required bindings.
    boolean needsNullChecked = injection.getRequiredBindings().isEmpty();
    if (needsNullChecked) {
      builder.append("    if (view != null) {\n");
      extraIndent = "  ";
    }

    for (Map.Entry<ListenerClass, MethodBinding> entry : methodBindings.entrySet()) {
      ListenerClass listener = entry.getKey();
      MethodBinding methodBinding = entry.getValue();

      // Emit: ((OWNER_TYPE) view).SETTER_NAME(
      boolean needsCast = !VIEW_TYPE.equals(listener.targetType());
      builder.append(extraIndent)
          .append("    ");
      if (needsCast) {
        builder.append("((").append(listener.targetType());
        if (listener.genericArguments() > 0) {
          builder.append('<');
          for (int i = 0; i < listener.genericArguments(); i++) {
            if (i > 0) {
              builder.append(", ");
            }
            builder.append('?');
          }
          builder.append('>');
        }
        builder.append(") ");
      }
      builder.append("view");
      if (needsCast) {
        builder.append(')');
      }
      builder.append('.')
          .append(listener.setter())
          .append("(\n");

      // Emit: new TYPE() {
      builder.append(extraIndent)
          .append("      new ")
          .append(listener.type())
          .append("() {\n");

      // Emit: @Override public RETURN_TYPE METHOD_NAME(
      builder.append(extraIndent)
          .append("        @Override public ")
          .append(listener.returnType())
          .append(' ')
          .append(listener.name())
          .append("(\n");

      // Emit listener method arguments, each on their own line.
      String[] parameterTypes = listener.parameters();
      for (int i = 0, count = parameterTypes.length; i < count; i++) {
        builder.append(extraIndent)
            .append("          ")
            .append(parameterTypes[i])
            .append(" p")
            .append(i);
        if (i < count - 1) {
          builder.append(',');
        }
        builder.append('\n');
      }

      // Emit end of parameters, start of body.
      builder.append(extraIndent).append("        ) {\n");

      // Emit call to target method using its parameter list.
      builder.append(extraIndent).append("          ");
      if (!"void".equals(listener.returnType())) {
        builder.append("return ");
      }
      builder.append("target.")
          .append(methodBinding.getName())
          .append('(');
      List<Parameter> parameters = methodBinding.getParameters();
      String[] listenerParameters = listener.parameters();
      for (int i = 0, count = parameters.size(); i < count; i++) {
        Parameter parameter = parameters.get(i);
        int listenerPosition = parameter.getListenerPosition();
        emitCastIfNeeded(builder, listenerParameters[listenerPosition], parameter.getType());
        builder.append('p').append(listenerPosition);
        if (i < count - 1) {
          builder.append(", ");
        }
      }
      builder.append(");\n");

      // Emit end of listener method.
      builder.append(extraIndent).append("        }\n");

      // Emit end of listener class body and close the setter method call.
      builder.append(extraIndent).append("      });\n");
    }

    if (needsNullChecked) {
      builder.append("    }\n");
    }
  }

  private void emitReset(StringBuilder builder) {
    builder.append("  public static void reset(").append(targetClass).append(" target) {\n");
    if (parentInjector != null) {
      builder.append("    ")
          .append(parentInjector)
          .append(".reset(target);\n\n");
    }
    for (ViewInjection injection : viewIdMap.values()) {
      for (FieldBinding fieldBinding : injection.getFieldBindings()) {
        builder.append("    target.").append(fieldBinding.getName()).append(" = null;\n");
      }
    }
    for (CollectionBinding collectionBinding : collectionBindings.keySet()) {
      builder.append("    target.").append(collectionBinding.getName()).append(" = null;\n");
    }
    builder.append("  }\n");
  }

  static void emitCastIfNeeded(StringBuilder builder, String viewType) {
    emitCastIfNeeded(builder, VIEW_TYPE, viewType);
  }

  static void emitCastIfNeeded(StringBuilder builder, String sourceType, String destinationType) {
    // Only emit a cast if the source and destination type do not match.
    if (!sourceType.equals(destinationType)) {
      builder.append('(').append(destinationType).append(") ");
    }
  }

  static void emitHumanDescription(StringBuilder builder, List<Binding> bindings) {
    switch (bindings.size()) {
      case 1:
        builder.append(bindings.get(0).getDescription());
        break;
      case 2:
        builder.append(bindings.get(0).getDescription())
            .append(" and ")
            .append(bindings.get(1).getDescription());
        break;
      default:
        for (int i = 0, count = bindings.size(); i < count; i++) {
          Binding requiredField = bindings.get(i);
          if (i != 0) {
            builder.append(", ");
          }
          if (i == count - 1) {
            builder.append("and ");
          }
          builder.append(requiredField.getDescription());
        }
        break;
    }
  }
}
