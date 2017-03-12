package SQLParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import SQLExpression.ColumnNode;
import SQLExpression.Expression;
import SQLExpression.StringValue;
import TableElement.Table;

/**
 * As the name suggests, this class mainly handles the selection part of
 * the SQL query. Notice in the parameter. The string in the argument
 * is prepared in advance: That means there will always a blank between
 * each attributes which will be useful for the split() method from the 
 * string. Also, for the aliases, there should always be an "AS" between
 * the original name and the newly created name.
 * @author messfish
 *
 */
public class PlainSelect {

	private String query; 
	// the variable stores the whole query language.
	private List<Expression> selectlist = new ArrayList<>();
	// this list stores a list of column names or operation trees.
	private List<String>  select_alias = new ArrayList<>();
	// this list stores a list of alias of the select expression.
	// If there are no alias, put an empty string inside it.
	private List<Expression> orderbylist = new ArrayList<>();
	// this list stores a list of expressions for order by clause.
	private List<String> groupbylist = new ArrayList<>();
	// this list stores a list of expressions for group by clause.
	private Map<String, Table> tablemap = new HashMap<>();
	// this list stores a map that stores the alias name with their
	// tables, if there are no alias, store the table name instead.
	// Note the alias is the key and the original table is the value.
	private Expression whereexpress;
	// this is the root of the where expression.
	private Expression havingexpress; 
	// this is the root of the having expression.
	private boolean isDescending;
	// this method is used to check whether we order the table elements
	// in the descending order or not, I set it to "true" when the order
	// is descending, "false" means the order is ascending.
	private int startpoint, endpoint;
	// this two value indicates the starting point and the ending point
	// of the order by query.
	private boolean isDistinct;
	// this value indicates whether the select query pick out distinct tuples.
	
	/**
	 * Constructor: It takes a query as argument and divide it into 
	 * different parts. And parse these parts independently.
	 * Note that the start of the query always be SELECT.
	 * The SELECT query must follows a FROM query.
	 * The Next of the FROM query could be these three parts:
	 * WHERE, GROUP BY, ORDER BY.
	 * The Next of the WHERE query could be these two parts:
	 * GROUP BY, ORDER BY.
	 * The Next of the GROUP BY query could be these two parts:
	 * HAVING, ORDER BY.
	 * The Next of the HAVING query could be the ORDER BY.
	 * The ORDER BY always leads to an end.
	 * @param s the string as the argument.
	 */
	public PlainSelect(String s) {
		query = s;
		String[] array = s.split("\\s+");
		int state = 0, index = 0;
		/* use the automation theory to divide the query. Notice the end of 
		 * the string array must be a semicolon, so skip it.*/
		while(index < array.length - 1) {
			switch(state){
			/* case 0 indicates the SELECT query */
			case 0: int[] state0 = findNext(array, "FROM", index + 1, 1);
					parseSelectPart(BuildString(array, index + 1, state0[0]));
					index = state0[0];
					state = state0[1];
					break;
			/* case 1 indicates the FROM query */
			case 1: String[] target0 = {"WHERE", "GROUP", "", "ORDER"};
					int[] state1 = findNextInArray(array, target0, index + 1, 2);
					int dummy1 = state1 == null? array.length - 1 : state1[0];
					parseFromPart(BuildString(array, index + 1, dummy1));
					index = dummy1;
					state = state1 == null? -1 : state1[1];
					break;
			/* case 2 indicates the WHERE query */
			case 2: String[] target1 = {"GROUP", "", "ORDER"};
					int[] state2 = findNextInArray(array, target1, index + 1, 3);
					int dummy2 = state2 == null? array.length - 1: state2[0];
					parseWherePart(BuildString(array, index + 1, dummy2));
					index = dummy2;
					state = state2 == null? -1 : state2[1];
					break;
			/* case 3 indicates the GROUP BY query */
			case 3: String[] target2 = {"HAVING", "ORDER"};
					int[] state3 = findNextInArray(array, target2, index + 2, 4);
					int dummy3 = state3 == null? array.length - 1: state3[0];
					parseGroupByPart(BuildString(array, index + 2, dummy3));
					index = dummy3;
					state = state3 == null? -1 : state3[1];
					break;
			/* case 4 indicates the HAVING query */
			case 4: int[] state4 = findNext(array, "ORDER", index + 1, 5);
					int dummy4 = state4 == null? array.length - 1: state4[0];
					parseHavingPart(BuildString(array, index + 1, dummy4));
					index = dummy4;
					state = state4 == null? -1 : state4[1];
					break;
			/* case 5 indicates the ORDER BY query 
			 * Normally, this will be the end of a query.*/
			case 5: parseOrderByPart(BuildString(array, index + 2, array.length - 1));
					index = array.length;
			}
		}
	}

	/**
	 * This method tries to find the next string which is displayed in the
	 * target. if it fails to find, return a null value. If it succeeds,
	 * return an array with to values. The first value represents the 
	 * index where the method finds the target string. The second value
	 * represents the next state the query is going into. 
	 * @param array the array represent the query string.
	 * @param target the string represent the target that will be found.
	 * @param index the integer represents the starting point.
	 * @param next the integer represents the next state the query will be.
	 * @return the array with the point of the target and the next state.
	 */
	private int[] findNext(String[] array, String target, int index, int next) {
		int numOfParenthesis = 0;
		// take down the number of parenthesis to prevent finding the target
		// string in a sub query.
		while(index < array.length) {
			if(array[index].equals(target) && numOfParenthesis == 0)
				break;
			if(array[index].equals("(")) numOfParenthesis++;
			if(array[index].equals(")")) numOfParenthesis--;
			index++;
		}
		if(index == array.length) return null;
		return new int[]{index, next};
	}
	
	/**
	 * This method is the iterative version of findNext(): it will take
	 * a target list and iteratively checking which one is the suitable
	 * one and returns that. If there are no arrays returned, just return
	 * a null value as usual.
	 * @param array the array represents the query string.
	 * @param targetlist the list of targets as strings.
	 * @param index the integer serves as the starting point.
	 * @param addpoints the difference between the current state and the 
	 * array index, so the elements in the targetlist must in specifically order.
	 * That is the reason why there is a blank in the target list.
	 * @return the array with the point of the target and the next state. 
	 */
	private int[] findNextInArray(String[] array, String[] targetlist,
			                      int index, int addpoints) {
		int[] state = null;
		for(int i = 0; i < targetlist.length; i++) {
			if(targetlist[i].equals("")) continue;
			state = findNext(array, targetlist[i], index, i + addpoints);
			if(state != null) break;
		}
		return state;
	}
	
	/**
	 * This method build the String from a string array from the starting
	 * point to the end point. Note each element should have an empty
	 * space attach to it for the future use.
	 * @param array the array represent the query string.
	 * @param point the starting point of the array.
	 * @param point the ending point of the array.
	 * @return the string represents the divided point.
	 */
	private String BuildString(String[] array, int point, int index) {
		StringBuilder sb = new StringBuilder();
		while(point<index) {
			sb.append(array[point]).append(" ");
			point++;
		}
		sb.deleteCharAt(sb.length() - 1);
		return sb.toString();
	}
	
	/**
	 * This method parse the SELECT part of the query and put the results
	 * into a list of expressions which will be a field variable. Generally
	 * it will split the s by the ',' character. For each substring in the 
	 * array, check whether the left part is a column name or is an 
	 * aggregation name(also do not forget the "AS"), and store the 
	 * elements (expression and alias, if there are) into the global 
	 * variables above. Note is the expression is just an '*', simply
	 * return an empty list.
	 * @param s the select string which will be parsed.
	 */
	private void parseSelectPart(String s) {
		String[] expressionlist = s.split(",");
		String[] reserved = {"COUNT", "AVG", "SUM", "MIN", "MAX"};
		Set<String> set = new HashSet<>();
		/* this indicates the select query only contains an "*", simply return. */
		if(expressionlist[0].equals("*")||
				(expressionlist.length>1&&expressionlist[1].equals("*"))) 
			return;
		if(expressionlist[0].equals("DISTINCT")) isDistinct = true;
		for(int i=0;i<reserved.length;i++)
			set.add(reserved[i]);
		for(String str : expressionlist) {
			String[] temp = str.trim().split("\\s+");
			/* this will handle the case when an "AS" exists. */
			if(temp.length > 1 && temp[temp.length-2].equals("AS")){
				/* left expression is a column name. */
				if(temp.length==3){
					Expression column = new ColumnNode(temp[0]);
					selectlist.add(column);
				}
				/* left expression is an aggregation. */
				else if(set.contains(temp[0])){
					String data = "";
					for(int i=0;i<temp.length-2;i++)
						data += temp[i];
					Expression aggregate = new ColumnNode(data);
					selectlist.add(aggregate);
				}else{
					String[] change = new String[temp.length - 2];
					for(int i=0;i<change.length;i++)
						change[i] = temp[i];
					CalculationParser cal = new CalculationParser(change);
					selectlist.add(cal.parse());
				}
				select_alias.add(temp[temp.length-1]);
			}
			/* this case is when there are no alias in there. */
			else{
				/* left expression is a column name. */
				if(temp.length==1){
					Expression column = new ColumnNode(temp[0]);
					selectlist.add(column);
				}
				/* left expression is an aggregation. */
				else if(set.contains(temp[0])){
					String data = "";
					for(int i=0;i<temp.length;i++)
						data += temp[i];
					Expression aggregate = new ColumnNode(data);
					selectlist.add(aggregate);
				}
				select_alias.add("");
			}
		}
	}
	
	/**
	 * This method parse the FROM part of the query and put the tables
	 * and their aliases into a list. split the from clause with the ","
	 * which are not in the parenthesis and store the original table
	 * (it could be either a select query or a table) and their aliases
	 * into the global map.
	 * @param s the from string which will be parsed.
	 */
	private void parseFromPart(String s) {
		/* as usual, use a variable to take down the number of 
		 * parenthesis, only when we meet a "," and the number of
		 * parenthesis is zero can we know we reach the end of a table expression */
		int index = 0, numofparenthesis = 0, point = 0;
		while(index <= s.length()) {
			if(index<s.length()&&s.charAt(index)=='(') numofparenthesis++;
			if(index<s.length()&&s.charAt(index)==')') numofparenthesis--;
			if(index==s.length()||(s.charAt(index)==','&&numofparenthesis==0)) {
				Table table = null;
				String sub = s.substring(point, index).trim();
				String[] array = sub.split("\\s+");
				/* a "(" usually indicates the table is a select query. */
				if(array[0].equals("(")){
					/* three elements should not be counted: the alias name,
					 * the alias command "AS", a right parenthesis. */
					String subselect = BuildString(array, 1, array.length - 3);
					PlainSelect subplain = new PlainSelect(subselect);
					table = new Table(subplain);
				}
				/* this is the case when an alias exist or only the 
				 * original table name presents. Since they share the 
				 * same code I put them together in the else category.*/
				else table = new Table(array[0]);
				tablemap.put(array[array.length - 1], table);
				point = index + 1; // set point to the next table part.
			}
			index++;
		}
	}
	
	/**
	 * This method parse the WHERE part of the query and put the result
	 * into an expression tree. At first parse the expression into the 
	 * expression tree. Next change the expression tree into the CNF
	 * form. Compare the result with the original expression to see
	 * which one will be more suitable.
	 * @param s the where string which will be parsed.
	 */
	private void parseWherePart(String s) {
		String[] array = s.split("\\s+");
	    LogicalExpressionParser logic = new LogicalExpressionParser(array);
	    Expression express = logic.parse();
	    CNFConverter cnf = new CNFConverter();
	    cnf.convert(express);
	    whereexpress = express;
	}
	
	/**
	 * This method parse the GROUP BY part of the query and put the result
	 * into a list of column names. Simply split them with the "," and get 
	 * rid of extra white spaces for each elements in the array and put 
	 * them in the result.
	 * @param s the group by string which will be parsed.
	 */
	private void parseGroupByPart(String s) {
		String[] array = s.split(",");
		for(String str : array) 
			groupbylist.add(str.trim());
	}
	
	/**
	 * This method parse the HAVING part of the query and put the result
	 * into an expression tree. At first, split the string by using white
	 * spaces, and then combine the aggregations together, regroup then into
	 * a new list of string with all aggregations combined, for example:
	 * COUNT ( DISTINCT * ) --> COUNT(DISTINCT*). After that, use the 
	 * methods in HavingParser to parse the array into a expression tree
	 * and store the root into the global variable. 
	 * @param s the having string which will be parsed.
	 */
	private void parseHavingPart(String s) {
		String[] str = s.split("\\s+");
		List<String> list = new ArrayList<>();
		/* The list of aggregation words. */
		String[] regrouplist = {"COUNT", "MIN", "MAX", "AVG", "SUM"};
		Set<String> set = new HashSet<>();
		for(String string : regrouplist)
			set.add(string);
		for(int i=0;i<str.length;i++){
			/* if the string is an aggregation word, combine the 
			 * aggregation together in one array element. */
			if(set.contains(str[i])) {
				String group = "";
				/* i will stop at the index where the string is ")"
				 * after that, for loop will increment to next point. */
				while(!str[i].equals(")")){
					group += str[i];
					i++;
				}
				group += ")";
				list.add(group);
			}
			/* if not, put the individual element into the list. */
			else list.add(str[i]);
		}
		String[] havingarray = new String[list.size()];
		for(int i=0;i<havingarray.length;i++)
			havingarray[i] = list.get(i);
		LogicalExpressionParser parse = new LogicalExpressionParser(havingarray);
		havingexpress = parse.parse();
	}

	/**
	 * This method parse the ORDER BY part of the query and put the result
	 * into a list of column names. Notice the elements in the order by 
	 * language could either be a calculation or just a node. So we store 
	 * the result in a list.
	 * @param s the order by string which will be parsed.
	 */
	private void parseOrderByPart(String s) {
		String[] array = s.split("\\s+");
		int index = 0;
		/* this for loop extracts the order by elements out without considering
		 * the "DESC" and "LIMIT". */
		List<String> list = new ArrayList<>();
		for(;index<=array.length;index++) {
			if(index==array.length||array[index].equals("DESC")||
					array[index].equals("LIMIT")||array[index].equals(",")) {
				String[] temp = new String[list.size()];
				for(int i=0;i<temp.length;i++)
					temp[i] = list.get(i);
				CalculationParser cal = new CalculationParser(temp);
				Expression root = cal.parse();
				orderbylist.add(root);
				list = new ArrayList<>();
				if(index<array.length&&array[index].equals(",")) continue;
				else break;
			}
			else list.add(array[index]);
		}
		if(index!=array.length&&array[index].equals("DESC")) {
			isDescending = true;
			index++;
		}
		if(index!=array.length&&array[index].equals("LIMIT")) {
			String get1 = array[index+1], get2 = array[index+3];
			startpoint = Integer.parseInt(get1);
			endpoint = Integer.parseInt(get2);
		}
	}
	
	/**
	 * this is the getter method of the query string.
	 * @return the query string.
	 */
	public String getQuery() {
		return query;
	}
	
	/**
	 * this is the getter method for the selection list.
	 * @return the select list.
	 */
	public List<Expression> getSelectElements() {
		return selectlist;
	}
	
	/**
	 * this is the getter method for the select alias list.
	 * @return the select alias list.
	 */
	public List<String> getSelectAlias() {
		return select_alias;
	}
	
	/**
	 * this is the getter method for the from map.
	 * @return the from map.
	 */
	public Map<String, Table> getFromList() {
		return tablemap;
	}
	
	/**
	 * this is the getter method of the where expression.
	 * @return the where expression tree.
	 */
	public Expression getWhereExpression() {
		return whereexpress;
	}
	
	/**
	 * this is the getter method for the group by list.
	 * @return the group by list.
	 */
	public List<String> getGroupByElements() {
		return groupbylist;
	}
	
	/**
	 * this is the getter method for the having expression tree.
	 * @return the root of the having expression tree.
	 */
	public Expression getHavingExpression() {
		return havingexpress;
	}
	
	/**
	 * this is the getter method for the order by list.
	 * @return the order by list.
	 */
	public List<Expression> getOrderByElements() {
		return orderbylist;
	}

	/**
	 * this method returns whether the query is descending or not.
	 * @return the value indicates whether the query is descending.
	 */
	public boolean isDesc() {
		return isDescending;
	}
	
	/**
	 * this is the getter method of the starting point.
	 * @return the starting point of the query.
	 */
	public int getStartPoint() {
		return startpoint;
	}
	
	/**
	 * this is the getter method of the ending point.
	 * @return the ending point of the query.
	 */
	public int getEndPoint() {
		return endpoint;
	}
	
	/**
	 * this is the getter method of whether the query is distinct or not.
	 * @return the boolean value shows the query is distinct.
	 */
	public boolean isDistinct() {
		return isDistinct;
	}
	
	/**
	 * This method is used to check whether two objects are equal. 
	 * It is mainly used for debugging.
	 * @param plain another plain select object to be checked.
	 * @return the boolean value about whether these two are equal.
	 */
	public boolean equals(PlainSelect plain) {
		return getQuery().equals(plain.getQuery());
	}
	
}
