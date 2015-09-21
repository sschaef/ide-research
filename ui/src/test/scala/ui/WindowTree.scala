package ui

sealed trait WindowTree
case class Rows(rows: Seq[WindowTree]) extends WindowTree
case class Columns(columns: Seq[WindowTree]) extends WindowTree
case class Window(divId: String) extends WindowTree

object WindowTreeCreator {

  case class WinInfo(winId: Int, row: Int, col: Int)

  def mkWindowTree(wins: Seq[WinInfo]): WindowTree = wins match {
    case Seq(WinInfo(id, _, _)) ⇒
      Rows(Seq(Window(s"window$id")))
    case WinInfo(id1, r1, c1) +: WinInfo(id2, r2, c2) +: xs ⇒
      if (r1 == r2) {
        val ret = mkCols(wins)
        if (ret._2.isEmpty)
          Rows(Seq(ret._1))
        else {
          Rows(ret._1 +: mkRows(ret._2).rows)
        }
      } else
        mkRows(wins)
  }

  private def mkRows(wins: Seq[WinInfo]): Rows = wins match {
    case Seq(WinInfo(id, _, _)) ⇒
      Rows(Seq(Window(s"window$id")))
    case WinInfo(id1, _, _) +: WinInfo(id2, r1, _) +: xs ⇒
      val ret = Rows(Seq(Window(s"window$id1"), Window(s"window$id2")))
      val rows = mkRows(xs)
      Rows(ret.rows ++ rows.rows)
  }

  private def mkCols(wins: Seq[WinInfo]): (Columns, Seq[WinInfo]) = wins match {
    case Seq(WinInfo(id, _, _)) ⇒
      Columns(Seq(Window(s"window$id"))) → Nil
    case WinInfo(id1, _, c1) +: WinInfo(id2, r1, c2) +: xs ⇒
      val cols = Columns(Seq(Window(s"window$id1"), Window(s"window$id2")))
      if (xs.isEmpty || r1 == xs.head.row) {
        val ret = mkCols(xs)
        Columns(cols.columns ++ ret._1.columns) → ret._2
      }
      else
        (cols, xs)
  }

}
