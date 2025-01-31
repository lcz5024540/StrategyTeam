package com.metaverse.coin.strategy.common.base.utils;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.*;

/**
 * ===============================
 * 备注：类值转换映射帮助类
 * ===============================
 */
@Slf4j
public class ConvertUtils {

	/**
	 * list列表类型转换类
	 *
	 * @param sources     原始数据list列表
	 * @param targetClass 转换后的class
	 * @param filters     目的类型的实例中的不需要设置的字段名称
	 * @return 目的类型实例list列表
	 */
	public final static <S, T> List<T> convertList(List<S> sources, final Class<T> targetClass,
			final String... filters) {

		Optional<List<S>> absentList = Optional.of(sources);

		if (absentList.isPresent()) {

			Collection<T> transformColl = Collections2.transform(absentList.get(), new Function<S, T>() {

				public T apply(S input) {

					return convertObject(input, targetClass, filters);
				}
			});

			return Lists.newArrayList(transformColl);
		}

		return Lists.newArrayList();
	}

	public static String[] getNullPropertyNames (Object source) {
        final BeanWrapper src = new BeanWrapperImpl(source);
        java.beans.PropertyDescriptor[] pds = src.getPropertyDescriptors();

        Set<String> emptyNames = new HashSet<String>();
        for(java.beans.PropertyDescriptor pd : pds) {
            Object srcValue = src.getPropertyValue(pd.getName());
            if (srcValue == null) emptyNames.add(pd.getName());
        }
        String[] result = new String[emptyNames.size()];
        return emptyNames.toArray(result);
    }

	public static void copyPropertiesIgnoreNull(Object src, Object target){

        BeanUtils.copyProperties(src, target, getNullPropertyNames(src));
    }

	/**
	 * 类型转换类
	 *
	 * @param source  原始类型实例
	 * @param target  目的类型
	 * @param filters 目的类型的实例中的不需要设置的字段名称
	 * @return 目的类型实例
	 */
	public final static <S, T> T convertObject(S source, Class<T> target, String... filters) {

		T t = null;
		try {

			t = target.newInstance();
			BeanUtils.copyProperties(Optional.of(source).get(), t, filters);
		} catch (Exception e) {

			log.error("Convert object throw exception is {}",e);
		}

		return t;
	}

	@SuppressWarnings({ "unchecked", "unused" })
	public static <T> boolean function(Object obj, Class<T> calzz) {

		if (obj == null) {

			return false;
		}
		try {

			T t = (T) obj;
			return true;
		} catch (ClassCastException e) {

			return false;
		}
	}

	/**
	 * Map转Object
	 * @param map map对象
	 * @param target 目标对象
	 * @param filters 不转换字段
	 * @return
	 * @throws
	 * @throws Exception
	 */
	public static <T> T mapToObject(Map<String, Object> map, Class<T> target, String... filters){

		try {

			T t = target.newInstance();

			Field[] fields = t.getClass().getDeclaredFields();

			for (Field field : fields) {

				 int mod = field.getModifiers();

				if(Modifier.isStatic(mod) || Modifier.isFinal(mod)){

					continue;
				}

				try {

					if(null == map.get(field.getName())) {

						continue;
					}

					field.setAccessible(true);
					if(!field.getType().getName().equals(map.get(field.getName()).getClass().getName())) {

						if("java.math.BigDecimal".equals(field.getType().getName())){

							field.set(t, new BigDecimal(map.get(field.getName()).toString()));
							continue;
						}
					}
					field.set(t, map.get(field.getName()));
				} catch (Exception e) {

					log.error("Map to object convert field throw exception is {}",e);
				}
			}

			BeanUtils.copyProperties(Optional.of(t).get(), t, filters);

			return t;
		} catch (Exception e) {

			log.error("Map to object throw exception is {}",e);
		}

		return null;
	}

	/**
	 * 对象转Map
	 * @param object
	 * @return
	 * @throws Exception
	 */
	public static Map beanToMap(Object object){
		Map<String, Object> map = new HashMap<String, Object>();
		try {
			Field[] fields = object.getClass().getDeclaredFields();
			for (Field field : fields) {
				field.setAccessible(true);
				map.put(field.getName(), field.get(object));
			}
		} catch (Exception e) {
			log.error("beanToMap error:",e);
		}
		return map;
	}

	/**
	 * map转对象
	 * @param map
	 * @param beanClass
	 * @param <T>
	 * @return
	 * @throws Exception
	 */
	public static <T> T mapToBean(Map map, Class<T> beanClass) {
		T object = null;
		try {
			object = beanClass.newInstance();
			Field[] fields = object.getClass().getDeclaredFields();
			for (Field field : fields) {
				int mod = field.getModifiers();
				if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) {
					continue;
				}
				field.setAccessible(true);
				if (map.containsKey(field.getName())) {
					field.set(object, map.get(field.getName()));
				}
			}
		} catch (Exception e) {
			log.error("mapToBean error:",e);
		}
		return object;
	}
}
