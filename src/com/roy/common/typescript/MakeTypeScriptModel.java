package com.roy.common.typescript;

import com.intellij.notification.*;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NonNls;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.*;
import java.util.stream.Collectors;
public class MakeTypeScriptModel extends AnAction {


    private static final NotificationGroup notificationGroup =
            new NotificationGroup("pojo2typescript.NotificationGroup", NotificationDisplayType.BALLOON, true);

    @NonNls
    private static final Map<String, Object> normalTypes = new HashMap<>();
    private static final Set<String> EXISTED_TYPE = new HashSet<>();

    private static final Map<PsiClass, Map<String, PojoInfo>> rootMap = new HashMap<>();

    private static final String STRING_NAME = "string";
    private static final String BOOLEAN_NAME = "boolean";
    private static final String NUMBER_NAME = "number";
    private static final String DATE_NAME = "string";


    static {
        normalTypes.put("Boolean", BOOLEAN_NAME);
        normalTypes.put("Float", NUMBER_NAME);
        normalTypes.put("Double", NUMBER_NAME);
        normalTypes.put("BigDecimal", NUMBER_NAME);
        normalTypes.put("Number", NUMBER_NAME);
        normalTypes.put("CharSequence", STRING_NAME);
        normalTypes.put("Date", DATE_NAME);
        normalTypes.put("LocalDateTime", DATE_NAME);
        normalTypes.put("LocalDate", DATE_NAME);
        normalTypes.put("LocalTime", DATE_NAME);
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        Project project = e.getProject();
        PsiElement elementAt = psiFile.findElementAt(editor.getCaretModel().getOffset());
        PsiClass selectedClass = PsiTreeUtil.getContextOfType(elementAt, PsiClass.class);

        try {
            Map<String, PojoInfo> kv = getFields(selectedClass);
            rootMap.put(selectedClass, kv);
            String json = toTypeScriptModel(selectedClass);
            StringSelection selection = new StringSelection(json);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, null);
            String message = "Convert " + selectedClass.getName() + " to JSON success, copied to clipboard.";
            Notification success = notificationGroup.createNotification(message, NotificationType.INFORMATION);
            Notifications.Bus.notify(success, project);


        } catch (PluginException ex) {
            Notification warn = notificationGroup.createNotification(ex.getMessage(), NotificationType.WARNING);
            Notifications.Bus.notify(warn, project);
        } catch (Exception ex) {
            Notification error = notificationGroup.createNotification("Convert to JSON failed.", NotificationType.ERROR);
            Notifications.Bus.notify(error, project);
        } finally {
            rootMap.clear();
        }
    }

    private static String toTypeScriptModel(PsiClass selectedClass, Map<String, PojoInfo> kv) {
        StringBuilder sb = new StringBuilder();
        sb.append("export interface ").append(selectedClass.getName()).append("{").append("\n");

        for (Map.Entry<String, PojoInfo> entry : kv.entrySet()) {
            sb.append("\t").append(entry.getKey()).append(": ").append(entry.getValue().getType()).append("; ");
            if (null != entry.getValue().getAnnotationRemark()) {
                sb.append("//").append(entry.getValue().getAnnotationRemark());
            }
            sb.append("\n");
        }
        sb.append("}\n\n");

        return sb.toString();
    }


    private static String toTypeScriptModel(PsiClass selectedClass) {
        StringBuilder sb = new StringBuilder();

        for (Map.Entry<PsiClass, Map<String, PojoInfo>> entry : rootMap.entrySet()) {
            sb.append(toTypeScriptModel(entry.getKey(), entry.getValue()));
        }

        return sb.toString();
    }

    private static Map<String, PojoInfo> getFields(PsiClass psiClass) {
        Map<String, PojoInfo> map = new LinkedHashMap<>();

        if (psiClass == null) {
            return map;
        }

        try {
            for (PsiField field : psiClass.getAllFields()) {
                PojoInfo pojoInfo = new PojoInfo();
                if ("serialVersionUID".equals(field.getName())) {
                    continue;
                }

                pojoInfo.setName(field.getName());
                pojoInfo.setType(typeResolve(field.getType(), 0).toString());

                PsiAnnotation annotation = field.getAnnotation("io.swagger.annotations.ApiModelProperty");
                if (null != annotation) {
                    PsiAnnotationMemberValue psiAnnotationMemberValue = annotation.findAttributeValue("value");
                    if (null == psiAnnotationMemberValue || StringUtil.isEmptyOrSpaces(psiAnnotationMemberValue.getText())) {
                        psiAnnotationMemberValue = annotation.findAttributeValue("name");
                    }

                    if (null != psiAnnotationMemberValue) {
                        String remark = psiAnnotationMemberValue.getText().replace("\"", "");
                        pojoInfo.setAnnotationRemark(remark);
                    }
                }

                map.put(field.getName(), pojoInfo);
            }
        } catch (Exception e) {
            System.out.println("error:" + e.getMessage());
            throw e;
        }

        return map;
    }


    private static Object typeResolve(PsiType type, int level) {

        level = ++level;

        if (type instanceof PsiPrimitiveType) {       //primitive Type

            return getDefaultValue(type);

        } else if (type instanceof PsiArrayType) {   //array type

            List<Object> list = new ArrayList<>();
            PsiType deepType = type.getDeepComponentType();
            list.add(typeResolve(deepType, level));
            return list;

        } else {    //reference Type

            Map<String, PojoInfo> map = new LinkedHashMap<>();

            PsiClass psiClass = PsiUtil.resolveClassInClassTypeOnly(type);

            if (psiClass == null) {
                return map;
            }

            if (psiClass.isEnum()) { // enum

                for (PsiField field : psiClass.getFields()) {
                    if (field instanceof PsiEnumConstant) {
                        return field.getName();
                    }
                }
                return "";

            } else {

                List<String> fieldTypeNames = new ArrayList<>();

                PsiType[] types = type.getSuperTypes();

                fieldTypeNames.add(type.getPresentableText());
                fieldTypeNames.addAll(Arrays.stream(types).map(PsiType::getPresentableText).collect(Collectors.toList()));

                if (fieldTypeNames.stream().anyMatch(s -> s.startsWith("Collection") || s.startsWith("Iterable"))) {// Iterable

                    PsiType deepType = PsiUtil.extractIterableTypeParameter(type, false);
                    return typeResolve(deepType, level).toString() + "[]";

                } else { // Object

                    List<String> retain = new ArrayList<>(fieldTypeNames);
                    retain.retainAll(normalTypes.keySet());
                    if (!retain.isEmpty()) {
                        return normalTypes.get(retain.get(0));
                    } else {

                        if (level > 500) {
                            throw new PluginException("This class reference level exceeds maximum limit or has nested references!");
                        }

                        if (!EXISTED_TYPE.contains(psiClass.getQualifiedName())) {
                            EXISTED_TYPE.add(psiClass.getQualifiedName());
                            map = getFields(psiClass);
                        }

                        rootMap.put(psiClass, map);

                        return psiClass.getName();
                    }
                }
            }
        }
    }


    public static Object getDefaultValue(PsiType type) {
        if (!(type instanceof PsiPrimitiveType)) return null;
        switch (type.getCanonicalText()) {
            case "boolean":
                return "boolean";
            case "byte":
                return "number";
            case "char":
                return "string";
            case "short":
                return "number";
            case "int":
                return "number";
            case "long":
                return "number";
            case "float":
                return "number";
            case "double":
                return "number";
            default:
                return null;
        }
    }
}
